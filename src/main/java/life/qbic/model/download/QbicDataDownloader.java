package life.qbic.model.download;

import ch.ethz.sis.openbis.generic.asapi.v3.IApplicationServerApi;
import ch.ethz.sis.openbis.generic.asapi.v3.dto.common.search.SearchResult;
import ch.ethz.sis.openbis.generic.asapi.v3.dto.dataset.DataSet;
import ch.ethz.sis.openbis.generic.asapi.v3.dto.dataset.id.DataSetPermId;
import ch.ethz.sis.openbis.generic.dssapi.v3.IDataStoreServerApi;
import ch.ethz.sis.openbis.generic.dssapi.v3.dto.datasetfile.DataSetFile;
import ch.ethz.sis.openbis.generic.dssapi.v3.dto.datasetfile.download.DataSetFileDownload;
import ch.ethz.sis.openbis.generic.dssapi.v3.dto.datasetfile.download.DataSetFileDownloadOptions;
import ch.ethz.sis.openbis.generic.dssapi.v3.dto.datasetfile.download.DataSetFileDownloadReader;
import ch.ethz.sis.openbis.generic.dssapi.v3.dto.datasetfile.fetchoptions.DataSetFileFetchOptions;
import ch.ethz.sis.openbis.generic.dssapi.v3.dto.datasetfile.id.IDataSetFileId;
import ch.ethz.sis.openbis.generic.dssapi.v3.dto.datasetfile.search.DataSetFileSearchCriteria;
import ch.systemsx.cisd.common.spring.HttpInvokerUtils;
import life.qbic.ChecksumReporter;
import life.qbic.DownloadException;
import life.qbic.DownloadRequest;
import life.qbic.util.ProgressBar;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.net.MalformedURLException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;

public class QbicDataDownloader {

  private static final Logger LOG = LogManager.getLogger(QbicDataDownloader.class);
  private final ChecksumReporter checksumReporter;
  private final int defaultBufferSize;
  private final boolean conservePaths;
  private final IApplicationServerApi applicationServer;
  private final IDataStoreServerApi dataStoreServer;
  private final String sessionToken;
  private static final int DEFAULT_DOWNLOAD_ATTEMPTS = 3;
  private boolean invalidChecksumOccurred = false;

  /**
   * Constructor for a QBiCDataLoaderInstance
   *
   * @param AppServerUri  The openBIS application server URL (AS)
   * @param DataServerUri The openBIS datastore server URL (DSS)
   * @param bufferSize    The buffer size for the InputStream reader
   * @param conservePaths Flag to conserve the file path structure during download
   * @param sessionToken The session token for the datastore & application servers
   */
  public QbicDataDownloader(
      String AppServerUri,
      String DataServerUri,
      int bufferSize,
      boolean conservePaths,
      ChecksumReporter checksumReporter,
      String sessionToken) {
    this.checksumReporter = checksumReporter;
    this.defaultBufferSize = bufferSize;
    this.conservePaths = conservePaths;
    this.sessionToken = sessionToken;

    if (!AppServerUri.isEmpty()) {
      this.applicationServer =
          HttpInvokerUtils.createServiceStub(
              IApplicationServerApi.class, AppServerUri + IApplicationServerApi.SERVICE_URL, 10000);
    } else {
      this.applicationServer = null;
    }
    if (!DataServerUri.isEmpty()) {
      this.dataStoreServer =
          HttpInvokerUtils.createStreamSupportingServiceStub(
              IDataStoreServerApi.class, DataServerUri + IDataStoreServerApi.SERVICE_URL, 10000);
    } else {
      this.dataStoreServer = null;
    }
  }

  private static Path getTopDirectory(Path path) {
    Path currentPath = Paths.get(path.toString());
    Path parentPath;
    while (currentPath.getParent() != null) {
      parentPath = currentPath.getParent();
      currentPath = parentPath;
    }
    return currentPath;
  }

  /**
   * Downloads the files that the user requested
   * checks whether any filtering option (suffix or regex) has been passed and applies filtering if needed
   */
  public void downloadRequestedFilesOfDatasets(
          List<String> ids, List<String> suffixes, List<String> regexPatterns, String filterType, QbicDataDownloader qbicDataDownloader) {
    QbicDataFinder qbicDataFinder =
        new QbicDataFinder(applicationServer, dataStoreServer, sessionToken, filterType);

    LOG.info(
        String.format(
            "%s provided openBIS identifiers have been found: %s",
            ids.size(), ids));

    // a suffix was provided -> only download files which contain the suffix string
    if (suffixes!=null && !suffixes.isEmpty()) {
      for (String ident : ids) {
        LOG.info(String.format("Downloading filtered files for provided identifier %s", ident));
        List<Map<String, List<DataSetFile>>> foundSuffixFilteredIDs =
            qbicDataFinder.findAllSuffixFilteredIDs(ident, suffixes);

        LOG.info(String.format("Number of files found: %s", countDatasets(foundSuffixFilteredIDs)));

        downloadFilesFilteredByIDs(ident, foundSuffixFilteredIDs);
      }
      // a regex pattern was provided -> only download files which contain the regex pattern
    } else if (regexPatterns!=null && !regexPatterns.isEmpty()) {
      for (String ident : ids) {
        LOG.info(String.format("Downloading files for provided identifier %s", ident));
        List<DataSetFile> foundRegexFilteredIDs =
            qbicDataFinder.findAllRegexFilteredIDs(ident, regexPatterns);

        LOG.info(String.format("Number of files found: %s", foundRegexFilteredIDs.size()));

        //downloadFilesFilteredByIDs(ident, foundRegexFilteredIDs);
      }
    } else {
      // no suffix or regex was supplied -> download or print all datasets
        for (String ident : ids) {
          Map<String, List<DataSet>> foundDataSets = qbicDataFinder.findAllDatasetsRecursive(ident);
          if (foundDataSets.size() > 0) {
            LOG.info(String.format("Downloading files for identifier %s", ident));
            LOG.info("Initialize download ...");
            int datasetDownloadReturnCode = -1;
            try {
              // for the sample code and aggregates datasets per sample code
              List<Map<String, List<DataSet>>> datasets = new ArrayList<>();
              datasets.add(foundDataSets);
              datasetDownloadReturnCode = qbicDataDownloader.downloadDataset(datasets);
            } catch (NullPointerException e) {
              LOG.error(
                  "Datasets were found by the application server, but could not be found on the datastore server for "
                      + ident
                      + "."
                      + " Try to supply the correct datastore server using a config file!");
            }

            if (datasetDownloadReturnCode != 0) {
              LOG.error("Error while downloading dataset: " + ident);
            } else if (!invalidChecksumOccurred) {
              LOG.info("Download successfully finished.");
            }
          } else {
            LOG.info("Nothing to download.");
          }
        }
      }
  }

  private String getFileName(DataSetFile file) {
    String filePath = file.getPermId().getFilePath();
    return filePath.substring(filePath.lastIndexOf("/") + 1);
  }

  private <T> Integer countDatasets(List<Map<String, List<T>>> datasetsPerSampleCode) {
    int sum = 0;
    for (Map<String, List<T>> entry : datasetsPerSampleCode) {
      for (String sampleCode : entry.keySet()) {
        sum += entry.get(sampleCode).size();
      }
    }
    return sum;
  }

  public static <T> int countDatasets(Map<String, List<T>> datasetsPerSampleCode) {
    return datasetsPerSampleCode.values().stream().mapToInt(List::size).sum();
  }

  /**
   * Downloads all IDs which were previously filtered by either suffixes or regexes
   *
   * @param ident Sample identifiers
   * @param foundFilteredDatasets
   * @throws IOException
   */
  private void downloadFilesFilteredByIDs(String ident,
      List<Map<String, List<DataSetFile>>> foundFilteredDatasets) {
    System.out.println(foundFilteredDatasets);
    if (foundFilteredDatasets.size() > 0) {
      LOG.info("Initialize download ...");
      int filesDownloadReturnCode = -1;
      try {
        for (Map<String, List<DataSetFile>> filesPerSample : foundFilteredDatasets) {
          for (String sampleCode : filesPerSample.keySet()) {
            List<DataSetFile> filteredDataSetFiles = withoutDirectories(
                filesPerSample.get(sampleCode));
            final DownloadRequest downloadRequest = new DownloadRequest(filteredDataSetFiles,
                sampleCode);
            filesDownloadReturnCode = downloadFiles(downloadRequest);
          }
        }
      } catch (NullPointerException e) {
        LOG.error(
            "Datasets were found by the application server, but could not be found on the datastore server for "
                + ident
                + "."
                + " Try to supply the correct datastore server using a config file!");
      }
      if (filesDownloadReturnCode != 0) {
        LOG.error("Error while downloading dataset: " + ident);
      } else if(!invalidChecksumOccurred) {
        LOG.info("Download successfully finished");
      }

    } else {
      LOG.info("Nothing to download.");
    }
  }

  /**
   * Download a given list of data sets
   *
   * @param dataSetList A list of data sets
   * @return 0 if successful, 1 else
   */
  private int downloadDataset(List<Map<String, List<DataSet>>> dataSetList) {
    for (Map<String, List<DataSet>> dataSetsPerSample : dataSetList) {
      downloadDataset(dataSetsPerSample);
    }
    return 0;
  }

  private void downloadDataset(Map<String, List<DataSet>> dataSetsPerSample) {

    for (Entry<String, List<DataSet>> entry : dataSetsPerSample.entrySet()) {
      String sampleCode = entry.getKey();
      List<DataSet> sampleDatasets = entry.getValue();
      for (DataSet sampleDataset : sampleDatasets) {
        DataSetPermId permID = sampleDataset.getPermId();
        DataSetFileSearchCriteria criteria = new DataSetFileSearchCriteria();
        criteria.withDataSet().withCode().thatEquals(permID.getPermId());
        SearchResult<DataSetFile> result =
            this.dataStoreServer.searchFiles(sessionToken, criteria,
                new DataSetFileFetchOptions());
        List<DataSetFile> filteredDataSetFiles = withoutDirectories(result.getObjects());
        final DownloadRequest downloadRequest = new DownloadRequest(filteredDataSetFiles,
            sampleCode, DEFAULT_DOWNLOAD_ATTEMPTS);
        downloadFiles(downloadRequest);
      }
    }
  }

  public static List<DataSetFile> withoutDirectories(List<DataSetFile> dataSetFiles) {
    Predicate<DataSetFile> notADirectory = dataSetFile -> !dataSetFile.isDirectory();
    return dataSetFiles.stream()
        .filter(notADirectory)
        .collect(Collectors.toList());
  }

  private void downloadFile(DataSetFile dataSetFile, Path prefix) throws IOException {
    DataSetFileDownloadOptions options = new DataSetFileDownloadOptions();
    options.setRecursive(false);
    IDataSetFileId fileId = dataSetFile.getPermId();
    InputStream stream =
            this.dataStoreServer.downloadFiles(
                    sessionToken, Collections.singletonList(fileId), options);
    DataSetFileDownloadReader reader = new DataSetFileDownloadReader(stream);
    DataSetFileDownload file;

    while ((file = reader.read()) != null) {
      InputStream initialStream = file.getInputStream();
      CheckedInputStream checkedInputStream = new CheckedInputStream(initialStream, new CRC32());
      if (file.getDataSetFile().getFileLength() > 0) {
        final Path filePath = determineFinalPathFromDataset(file.getDataSetFile());
        File newFile =
                new File(System.getProperty("user.dir") +
                        File.separator +
                        prefix.toString() + File.separator +
                        filePath.toString());
        newFile.getParentFile().mkdirs();
        OutputStream os = new FileOutputStream(newFile);
        String fileName = filePath.getFileName().toString();
        ProgressBar progressBar =
                new ProgressBar(
                        fileName, file.getDataSetFile().getFileLength());
        int bufferSize =
                (file.getDataSetFile().getFileLength() < defaultBufferSize)
                        ? (int) file.getDataSetFile().getFileLength()
                        : defaultBufferSize;
        byte[] buffer = new byte[bufferSize];
        int bytesRead;
        LOG.info(String.format("Download of %s is starting", fileName));
        // read from is to buffer
        while ((bytesRead = checkedInputStream.read(buffer)) != -1) {
          progressBar.updateProgress(bufferSize);
          os.write(buffer, 0, bytesRead);
          os.flush();
        }
        initialStream.close();
        // flush OutputStream to write any buffered data to file
        os.flush();
        LOG.info(String.format("Download of %s has finished", fileName));
        validateChecksum(
                Long.toHexString(checkedInputStream.getChecksum().getValue()), dataSetFile);
        if(invalidChecksumOccurred) {
          notifyUserOfInvalidChecksum(dataSetFile);
        }
        os.close();
      }
    }
  }

  private void validateChecksum(String computedChecksumHex, DataSetFile dataSetFile) {
    String expectedChecksum = Integer.toHexString(dataSetFile.getChecksumCRC32());
    try {
      if (computedChecksumHex.equals(expectedChecksum)) {
        checksumReporter.reportMatchingChecksum(
            expectedChecksum,
            computedChecksumHex,
            Paths.get(dataSetFile.getPath()).toUri().toURL());
      } else {
        checksumReporter.reportMismatchingChecksum(
            expectedChecksum,
            computedChecksumHex,
            Paths.get(dataSetFile.getPath()).toUri().toURL());
        invalidChecksumOccurred = true;
      }

    } catch (MalformedURLException e) {
      LOG.error(e);
    }
  }

  private Path determineFinalPathFromDataset(DataSetFile file) {
    Path finalPath;
    if (conservePaths) {
      finalPath = Paths.get(file.getPath());
      // drop top parent directory name in the openBIS DSS (usually "/origin")
      Path topDirectory = getTopDirectory(finalPath);
      finalPath = topDirectory.relativize(finalPath);
    } else {
      finalPath = Paths.get(file.getPath()).getFileName();
    }
    return finalPath;
  }

  private int downloadFiles(DownloadRequest request) throws DownloadException {
    String sampleCode = request.getSampleCode();
    LOG.info(String.format("Downloading file(s) for sample %s", sampleCode));
    Path pathPrefix = Paths.get(sampleCode + File.separator);
    request
        .getDataSets()
        .forEach(
            dataSetFile -> {
              try {
                int downloadAttempt = 1;
                while (downloadAttempt <= request.getMaxNumberOfAttempts()) {
                  try {
                    downloadFile(dataSetFile, pathPrefix);
                    writeCRC32Checksum(dataSetFile, pathPrefix);
                    return;
                  } catch (Exception e) {
                    LOG.error(String.format("Download attempt %d failed.", downloadAttempt));
                    LOG.error(String.format("Reason: %s", e.getMessage()), e);
                    downloadAttempt++;
                    if (downloadAttempt > request.getMaxNumberOfAttempts()) {
                      throw new IOException("Maximum number of download attempts reached.");
                    }
                  }
                }
              } catch (IOException e) {
                String fileName = Paths.get(dataSetFile.getPath()).getFileName().toString();
                LOG.error(e);
                throw new DownloadException(
                    "Dataset " + fileName + " could not have been downloaded.");
              }
            });
    return 0;
  }

  private void writeCRC32Checksum(DataSetFile dataSetFile, Path pathPrefix) {
    Path path = Paths.get(pathPrefix.toString(), File.separator,
        determineFinalPathFromDataset(dataSetFile).toString());
    checksumReporter.storeChecksum(path, Integer.toHexString(dataSetFile.getChecksumCRC32()));
  }

  public void notifyUserOfInvalidChecksum(DataSetFile file) {
    LOG.warn(String.format("Checksum mismatches were detected for file %s. For more Information check the logs/summary_invalid_files.txt log file." , getFileName(file)));
  }

}
