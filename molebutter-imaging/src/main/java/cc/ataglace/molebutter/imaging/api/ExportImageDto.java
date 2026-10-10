package cc.ataglace.molebutter.imaging.api;

/** A bounded image export. Filename is a basename; the server determines its category and actual extension. */
public record ExportImageDto(String fileName, String contentType, String fileExtension, byte[] bytes,
        ExportImageCategory category) {
    public ExportImageDto { bytes = bytes.clone(); }
    @Override public byte[] bytes() { return bytes.clone(); }
    public int byteSize() { return bytes.length; }
}
