package cc.ataglace.molebutter.imaging.api;
/** An owned, completed ZIP snapshot. */
public record DownloadArtifact(String downloadName, byte[] bytes) {
    public DownloadArtifact { bytes = bytes.clone(); }
    @Override public byte[] bytes() { return bytes.clone(); }
}
