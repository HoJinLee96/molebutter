package cc.ataglace.molebutter.coupang.api;
import java.util.Map;
import java.util.List;
/** Account scoped local registration defaults. Never a seller write. */
public interface CoupangRegistrationDefaults {
    record BrandLogo(String brandName,List<String> aliases,String imageUrl) {}
    record Settings(String accountKey,long revision,Map<String,String> delivery,Map<String,String> registration,Map<String,String> settings,List<CoupangCatalog.Certification> certifications,List<String> descriptionPrefixImages,List<BrandLogo> brandLogos) {
        public Settings { descriptionPrefixImages=descriptionPrefixImages==null?List.of():List.copyOf(descriptionPrefixImages);brandLogos=brandLogos==null?List.of():List.copyOf(brandLogos); }
        public Settings(String accountKey,long revision,Map<String,String> delivery,Map<String,String> registration,Map<String,String> settings,List<CoupangCatalog.Certification> certifications,List<String> descriptionPrefixImages){this(accountKey,revision,delivery,registration,settings,certifications,descriptionPrefixImages,List.of());}
        public Settings(String accountKey,long revision,Map<String,String> delivery,Map<String,String> registration,Map<String,String> settings,List<CoupangCatalog.Certification> certifications){this(accountKey,revision,delivery,registration,settings,certifications,List.of());}
        public Settings(String accountKey,long revision,Map<String,String> delivery,Map<String,String> registration,Map<String,String> settings){this(accountKey,revision,delivery,registration,settings,List.of());}
    }
    record Save(Long revision,Map<String,String> delivery,Map<String,String> registration,Map<String,String> settings,List<CoupangCatalog.Certification> certifications,List<String> descriptionPrefixImages,List<BrandLogo> brandLogos) {
        public Save(Long revision,Map<String,String> delivery,Map<String,String> registration,Map<String,String> settings,List<CoupangCatalog.Certification> certifications,List<String> descriptionPrefixImages){this(revision,delivery,registration,settings,certifications,descriptionPrefixImages,null);}
        public Save(Long revision,Map<String,String> delivery,Map<String,String> registration,Map<String,String> settings,List<CoupangCatalog.Certification> certifications){this(revision,delivery,registration,settings,certifications,null);}
        public Save(Long revision,Map<String,String> delivery,Map<String,String> registration,Map<String,String> settings){this(revision,delivery,registration,settings,List.of());}
    }
    Settings get(Long actor);
    Settings save(Long actor,Save input);
}
