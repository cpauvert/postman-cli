package life.qbic.util;


import life.qbic.model.UnitConverter.UnitConverterFactory;
import life.qbic.model.UnitConverter.UnitDisplay;

public class ProgressBar {

    private float nextProgressJump;
    private float stepSize;
    private String fileName;
    private Long totalFileSize;
    private Long downloadedSize;
    private final int BARSIZE = jline.TerminalFactory.get().getWidth()/3;
    private final int MAXFILENAMESIZE = jline.TerminalFactory.get().getWidth()/3;
    private UnitDisplay unitDisplay;
    private long start;

    public ProgressBar(){}

    public ProgressBar(String fileName, long totalFileSize){
        this.fileName = shortenFileName(fileName);
        this.totalFileSize = totalFileSize;
        this.downloadedSize = 0L;
        this.stepSize = totalFileSize / BARSIZE;
        this.nextProgressJump = this.stepSize;
        this.unitDisplay = UnitConverterFactory.determineBestUnitType(totalFileSize);
        this.start = System.currentTimeMillis();
    }

    public void updateProgress(int addDownloadedSize){
        this.downloadedSize += (long) addDownloadedSize;
        checkForJump();
    }

    private String shortenFileName(String fullFileName){
        String shortName;
        if (fullFileName.length() > MAXFILENAMESIZE){
            shortName = fullFileName.substring(0, MAXFILENAMESIZE-3) + "...";
        } else {
            shortName = fullFileName;
        }
        return shortName;
    }

    private void checkForJump() {
        if(this.downloadedSize > this.nextProgressJump){
            this.nextProgressJump += this.stepSize;
            drawProgress();
        }
    }

    private void drawProgress() {
        System.out.print(String.format("%-" + computeLeftPadding() +"s %s\r", this.fileName,buildProgressBar()));
    }

    private int computeLeftPadding(){
        return MAXFILENAMESIZE + 5;
    }


    private String buildProgressBar(){
        StringBuilder progressBar = new StringBuilder("[");
        int numberProgressStrings = Math.min((int) (this.downloadedSize / this.stepSize), BARSIZE);

        double elapsedTimeSeconds = (System.currentTimeMillis() - this.start) / 1000.0;

        // Download Speed in Mbyte/s
        double downloadSpeed = this.downloadedSize / ( 1000000.0 * elapsedTimeSeconds );

        for (int i = 0; i < numberProgressStrings; i++){
            progressBar.append("#");
        }
        for (int i = numberProgressStrings; i<BARSIZE; i++){
            progressBar.append(" ");
        }

        progressBar.append("]\t");
        progressBar.append(String.format("%6.02f/%-7.02f%s (%.02f Mb/s)", unitDisplay.convertBytesToUnit(this.downloadedSize),
                unitDisplay.convertBytesToUnit(this.totalFileSize), unitDisplay.getUnitType(), downloadSpeed));

        return progressBar.toString();
    }


}

