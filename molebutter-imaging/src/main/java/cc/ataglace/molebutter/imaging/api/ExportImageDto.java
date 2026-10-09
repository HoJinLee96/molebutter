package cc.ataglace.molebutter.imaging.api;

/** A bounded image export. Filename is the browser name; extension follows the actual content type. */
public record ExportImageDto(String fileName, String contentType, String fileExtension, byte[] bytes) {
    public ExportImageDto { bytes = bytes.clone(); }
    @Override public byte[] bytes() { return bytes.clone(); }
    public int byteSize() { return bytes.length; }
}
