package cc.ataglace.molebutter.media.api;

/** Server-generated, immutable edits of verified R2 originals. */
public interface ImageEdits {
    record Rect(int x,int y,int width,int height) {}
    record Source(String token,String previewUrl,String originalUrl,int width,int height,Rect rect) {}
    record Request(String token,Rect rect) {}
    Source open(Long actor,String url,String assetId);
    ImageAssets.AssetContent preview(Long actor,String token);
    ImageAssets.Asset crop(Long actor,Request request);
}
