package cc.ataglace.molebutter.imaging.api;

/** 내보낼 파일의 출처를 서버가 확인한 저장 구분. */
public enum ExportImageCategory {
    OFFICIAL("official"),
    PROCESSED("processed");

    private final String directory;
    ExportImageCategory(String directory) { this.directory = directory; }
    public String directory() { return directory; }
}
