package cc.ataglace.molebutter;

import static org.assertj.core.api.Assertions.assertThat;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import com.tngtech.archunit.core.importer.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ModuleArchitectureTest {
    private static final String ROOT="cc.ataglace.molebutter.";
    private static final Map<String,Set<String>> ALLOWED=Map.of(
        "common",Set.of(), "identity",Set.of("common"), "operations",Set.of("common","identity"),
        "catalog",Set.of("common","identity","operations"),
        "procurement",Set.of("common","identity","operations","catalog"),
        "inventory",Set.of("common","identity","operations","catalog"),
        "attendance",Set.of("common","identity","operations"),
        "app",Set.of("common","identity","operations","catalog","procurement","inventory","attendance"));
    private static String owner(String name){return name.startsWith(ROOT)?name.substring(ROOT.length()).split("\\.")[0]:"";}
    @Test void businessDependenciesUsePublicContractsAndHaveNoCycles() {
        var classes=new ClassFileImporter().withImportOption(location -> !location.toString().contains("/test-classes/") && !location.toString().contains("-tests.jar")).importPackages("cc.ataglace.molebutter");
        var violations=new TreeSet<String>();
        for(var type:classes){
            String from=owner(type.getName());if(!ALLOWED.containsKey(from))continue;
            for(var dependency:type.getDirectDependenciesFromSelf()){
                var target=dependency.getTargetClass();String to=owner(target.getName());
                if(!ALLOWED.containsKey(to)||from.equals(to))continue;
                if(!ALLOWED.get(from).contains(to)||!(target.getPackageName().equals(ROOT+to+".api")||target.getPackageName().startsWith(ROOT+to+".api.")||to.equals("common")&&target.getPackageName().equals(ROOT+"common.persistence")))violations.add(dependency.getDescription());
            }
        }
        assertThat(violations).as("Only declared module dependencies and public contracts may cross owners").isEmpty();
        slices().matching("cc.ataglace.molebutter.(*)..").should().beFreeOfCycles().check(classes);
    }
}
