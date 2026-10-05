package cc.ataglace.molebutter.catalog.api;
import cc.ataglace.molebutter.common.api.NamedSettingInput;
import java.util.List;
public interface SharedSettingsService {
    public record RevisionInput(Long revision) {}
    public record Brand(String id, String name, long revision, long usageCount,String codeBrand) {}
    List<Brand> brands(Long actor);
    Brand saveBrand(Long actor, Long id, NamedSettingInput input);
    void deleteBrand(Long actor,long id,Long revision);
}
