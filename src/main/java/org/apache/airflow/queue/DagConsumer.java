package org.apache.airflow.queue;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;

import org.apache.airflow.AirflowConfig;
import org.apache.airflow.cache.DagCache;
import org.apache.airflow.cache.DagInstance;
import org.apache.airflow.cache.UnregisteredDagCache;
import org.apache.airflow.cache.UnregisteredDagInstance;
import org.apache.airflow.crd.DagSpec;
import org.apache.airflow.database.AirflowDag;
import org.apache.airflow.database.DatasourceService;
import org.apache.airflow.service.DagService;
import org.apache.airflow.type.ControlType;
import org.apache.airflow.type.DagType;
import org.apache.airflow.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DagConsumer extends Thread {

    public static final String EMPTY_FILE = "";

    private static final Logger LOGGER = LoggerFactory.getLogger(DagConsumer.class);

    private final DagService dagService;

    private final AirflowConfig airflowConfig;

    private final DatasourceService datasourceService;

    private final BlockingQueue<DagTask> dagQueue;

    private final int thread;

    public DagConsumer(DagService dagService, AirflowConfig airflowConfig, DatasourceService datasourceService,
                       BlockingQueue<DagTask> dagQueue, int thread) {
        this.dagService = dagService;
        this.airflowConfig = airflowConfig;
        this.datasourceService = datasourceService;
        this.dagQueue = dagQueue;
        this.thread = thread;
    }

    public int getThread() {
        return thread;
    }

    @Override
    public void run() {
        Thread.currentThread().setName("DagConsumer-Thread-" + getThread());
        while (true) {
            try {
                DagTask task = dagQueue.take();
                if (task.getType() == ControlType.delete) {
                    deleteFile(task);
                } else {
                    String name = task.getName();
                    long version = task.getVersionNum();
                    String fullPath;
                    if (!DagCache.CACHE.contains(name)) {
                        LOGGER.debug("Creating {} {} ...", task.getSpec().getType(), name);
                        fullPath = createFile(task);
                    } else {
                        DagInstance oldTask = DagCache.CACHE.getInstance(name);
                        long lastVersion = oldTask.getVersion();
                        // Check if this version is larger than this version
                        if (lastVersion <= version) {
                            LOGGER.debug("Updating {} {} ...", task.getSpec().getType(), name);
                            fullPath = updateFile(task, oldTask);
                        } else {
                            LOGGER.warn("Can not create dag {}, current version {}, cache version {}",
                                    name, version, DagCache.CACHE.getInstance(name).getVersion());
                            continue;
                        }
                    }

                    // If support pause
                    if (task.getSpec().getType() != DagType.file && airflowConfig.supportPause()) {
                        String dagId = task.getSpec().getDagName();
                        Optional<AirflowDag> dag = datasourceService.getAirflowDag(dagId);
                        dag.ifPresentOrElse(d -> {
                            // If paused is different from airflow dag table, call the dag pause command
                            if (!task.getSpec().getPaused().equals(d.isPaused())) {
                                LOGGER.info("Need to set dag {} paused to {}", dagId, task.getSpec().getPaused());
                                dagService.pauseDag(dagId, task.getSpec().getPaused());
                            }
                        }, () -> {
                            // We need to queue the ungenerated DAG and recheck it
                            if (!EMPTY_FILE.equals(fullPath)) {
                                try {
                                    LOGGER.debug("Check if dag {} is imported error in path {}", dagId, fullPath);
                                    if (!datasourceService.importErrorDags(fullPath)) {
                                        LOGGER.info("Dag {} is not imported error, we need to add it to queue and recheck after",
                                                dagId);
                                        // Put the tasks that the scheduler did not generate DAG tasks into the cache,
                                        // and wait for asynchronous recheck again later
                                        UnregisteredDagCache.CACHE.cache(dagId, new UnregisteredDagInstance(
                                                dagId, fullPath, task.getNamespace(), task.getName(),
                                                task.getSpec().getPaused()));
                                    }
                                } catch (SQLException e) {
                                    LOGGER.error("Error when check dag is imported error", e);
                                }
                            }
                        });
                    }
                }
            } catch (Exception e) {
                LOGGER.error("Get Dag Queue error！", e);
            }
        }
    }

    /**
     * Delete task file
     */
    private void deleteFile(DagTask task) throws IOException {
        String name = task.getName();
        final StringBuilder filePath = new StringBuilder();

        if (DagCache.CACHE.contains(name)) {// if cached
            DagCache.CACHE.getInstance(name, task.getVersionNum())
                    .ifPresentOrElse(
                            dagInstance -> filePath.append(dagInstance.getFilePath()),
                            () -> LOGGER.warn("Can not delete dag {}, current version {}, cache version {}",
                                    name, task.getVersion(), DagCache.CACHE.getInstance(name).getVersion()));
        } else {
            DagSpec spec = task.getSpec();
            DagType type = spec.getType();
            switch (type) {
                case file:
                    filePath.append(dagService.getPath(spec.getPath()))
                            .append(spec.getFileName());
                    break;
                case dag_yaml:
                case dag_file:
                default:
                    filePath.append(dagService.getPath(spec.getPath()))
                            .append(dagService.getDagFile(name, spec.getDagName()));
                    break;
            }
        }

        if (filePath.length() > 0) {
            LOGGER.info("Delete dag {} in path {}", name, filePath);
            dagService.deleteFilePath(filePath.toString());
        }
    }

    /**
     * Create task file
     */
    public String createFile(DagTask task) {
        DagSpec spec = task.getSpec();
        String name = task.getName();

        // get file path and name
        FilePath fp = dagService.getFilePath(task);
        String filePath = fp.getPath();
        String fileName = fp.getFileName();
        DagInstance di = new DagInstance(name, task.getVersionNum())
                .setType(spec.getType())
                .setPath(filePath)
                .setFileName(fileName);

        // get file content
        String content = dagService.getFileContent(task);
        di.setContent(content);

        // create file
        String fullPath = createFileContent(filePath, fileName, content);

        // save cache
        LOGGER.trace("Saving to cache {}", di);
        DagCache.CACHE.cache(name, di);
        return fullPath;
    }

    /**
     * Update task file
     */
    public String updateFile(DagTask task, DagInstance oldTask) throws IOException {
        DagSpec spec = task.getSpec();
        String name = task.getName();

        // get file path and name
        FilePath fp = dagService.getFilePath(task);
        String filePath = fp.getPath();
        String fileName = fp.getFileName();
        // get file content
        String newContent = dagService.getFileContent(task);

        DagInstance di = new DagInstance(name, task.getVersionNum())
                .setType(spec.getType())
                .setPath(filePath)
                .setFileName(fileName)
                .setContent(newContent);

        // Detect whether the data needs to be updated
        String oldPath = oldTask.getFilePath();
        String newPath = filePath + fileName;
        String fullPath = EMPTY_FILE;
        if (!oldPath.equals(newPath)) {
            // 1. path or file name had been changed, need to remove old file
            LOGGER.info("Need to delete old dag {} in path {}", name, oldPath);
            dagService.deleteFilePath(oldPath);

            LOGGER.info("Create new dag {} in path {}", name, oldPath);
            fullPath = createFileContent(filePath, fileName, newContent);
        } else {
            Path newFile = Paths.get(newPath);
            if (!Files.exists(newFile)) {
                // 2. if file not exists, create it
                LOGGER.info("Can not find exists dag, create it!");
                fullPath = createFileContent(filePath, fileName, newContent);
            } else {
                // 3. content had been changed, just use createFile method
                if (StringUtils.equals(newContent, oldTask.getContent())) {
                    LOGGER.debug("There is no difference between old and new contents!");
                } else {
                    LOGGER.info("The two contents are different, and the file needs to be rewritten!");
                    fullPath = createFileContent(filePath, fileName, newContent);
                }
            }
        }

        // save cache
        LOGGER.trace("Saving to cache {}", di);
        DagCache.CACHE.cache(name, di);
        return fullPath;
    }

    /**
     * Create dag file
     *
     * @param path     file path
     * @param fileName file name
     * @param content  file content
     */
    private String createFileContent(String path, String fileName, String content) {
        LOGGER.info("Create {} stored in {} and content \n{}", fileName, path, content);
        try {
            // create folder if not exists
            Path folderFile = Paths.get(path);
            if (!Files.exists(folderFile)) {
                LOGGER.debug("Folder not exists, create folder {} ...", path);
                Files.createDirectories(folderFile);
            }

            // write file overwrite
            String filePath = path + fileName;
            bufferedWriter(filePath, content);
            return filePath;
        } catch (IOException e) {
            LOGGER.error("Dag file error！", e);
            return EMPTY_FILE;
        }
    }

    /**
     * write dag
     */
    private void bufferedWriter(String filepath, String content) throws IOException {
        LOGGER.debug("Saving dag file to {} ...", filepath);
        try (FileWriter fileWriter = new FileWriter(filepath);
             BufferedWriter bufferedWriter = new BufferedWriter(fileWriter)) {
            bufferedWriter.write(content);
        }
    }
}
