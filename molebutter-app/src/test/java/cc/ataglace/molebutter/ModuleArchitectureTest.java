package cc.ataglace.molebutter;

import static org.assertj.core.api.Assertions.assertThat;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.*;
import java.lang.reflect.*;
import java.net.URI;
import java.util.*;
import org.junit.jupiter.api.Test;

class ModuleArchitectureTest {
    private static final String ROOT = "cc.ataglace.molebutter.";
    private static final Map<String,Set<String>> ALLOWED = Map.ofEntries(
        Map.entry("common",Set.of()), Map.entry("identity",Set.of("common")), Map.entry("operations",Set.of("common","identity")),
        Map.entry("catalog",Set.of("common","identity","operations")),
        Map.entry("procurement",Set.of("common","identity","operations","catalog")),
        Map.entry("inventory",Set.of("common","identity","operations","catalog")),
        Map.entry("attendance",Set.of("common","identity","operations")),
        Map.entry("media",Set.of("common","identity","storage")),
        Map.entry("marketplace",Set.of("common","identity","media")),
        Map.entry("marketplacecoupang",Set.of("common","identity","marketplace","media")),
        Map.entry("marketplacenaver",Set.of("common","identity","marketplace","media")),
        Map.entry("marketplacelotteon",Set.of("common","identity","marketplace","media")),
        Map.entry("marketplaceelevenst",Set.of("common","identity","marketplace","media")),
        Map.entry("marketplaceesm",Set.of("common","identity","marketplace","media")),
        Map.entry("imaging",Set.of("common")),
        Map.entry("storage",Set.of("common")),
        Map.entry("app",Set.of("common","identity","operations","catalog","procurement","inventory","attendance","marketplace","marketplacecoupang","marketplacenaver","marketplacelotteon","marketplaceesm","marketplaceelevenst","media","imaging","storage")));
    private static String owner(String name) { return name.startsWith(ROOT) ? name.substring(ROOT.length()).split("\\.")[0] : ""; }
    private static boolean api(String name, String owner) { return name.equals(ROOT+owner+".api") || name.startsWith(ROOT+owner+".api."); }
    private static boolean sourceMatches(URI source, String owner) {
        String location = source.toString();
        String module = switch(owner) { case "marketplacecoupang" -> "marketplace-coupang"; case "marketplacenaver" -> "marketplace-naver"; case "marketplacelotteon" -> "marketplace-lotteon"; case "marketplaceesm" -> "marketplace-esm"; case "marketplaceelevenst" -> "marketplace-elevenst"; default -> owner; };
        return location.contains("/molebutter-"+module+"/") || location.matches(".*[/!]molebutter-"+module+"-[^/!]+\\.jar.*");
    }
    private static void exposed(Type type, Set<Type> visited, Set<String> violations, String contract) {
        if (!visited.add(type)) return;
        if (type instanceof Class<?> c) {
            if (c.isArray()) exposed(c.getComponentType(),visited,violations,contract);
            else if (c.getName().startsWith(ROOT) && c.getPackageName().contains(".internal"))
                violations.add("Internal type in public signature: "+contract+" -> "+c.getName());
        } else if (type instanceof ParameterizedType p) {
            exposed(p.getRawType(),visited,violations,contract);
            for (Type t:p.getActualTypeArguments()) exposed(t,visited,violations,contract);
        } else if (type instanceof GenericArrayType a) exposed(a.getGenericComponentType(),visited,violations,contract);
        else if (type instanceof WildcardType w) {
            for (Type t:w.getUpperBounds()) exposed(t,visited,violations,contract);
            for (Type t:w.getLowerBounds()) exposed(t,visited,violations,contract);
        } else if (type instanceof TypeVariable<?> v)
            for (Type t:v.getBounds()) exposed(t,visited,violations,contract);
    }
    private static void publicSignatures(Class<?> c, Set<String> violations) {
        if (!Modifier.isPublic(c.getModifiers())) return;
        var types = new ArrayList<Type>();
        types.addAll(Arrays.asList(c.getTypeParameters()));
        if (c.getGenericSuperclass()!=null) types.add(c.getGenericSuperclass());
        types.addAll(Arrays.asList(c.getGenericInterfaces()));
        for (var m:c.getDeclaredMethods()) if (Modifier.isPublic(m.getModifiers())) {
            types.addAll(Arrays.asList(m.getTypeParameters()));
            types.add(m.getGenericReturnType()); types.addAll(Arrays.asList(m.getGenericParameterTypes()));
            types.addAll(Arrays.asList(m.getGenericExceptionTypes()));
        }
        for (var constructor:c.getConstructors()) {
            types.addAll(Arrays.asList(constructor.getTypeParameters()));
            types.addAll(Arrays.asList(constructor.getGenericParameterTypes()));
            types.addAll(Arrays.asList(constructor.getGenericExceptionTypes()));
        }
        for (var field:c.getDeclaredFields()) if (Modifier.isPublic(field.getModifiers())) types.add(field.getGenericType());
        if (c.isRecord()) for (var component:c.getRecordComponents()) types.add(component.getGenericType());
        var visited = new HashSet<Type>();
        for (var type:types) exposed(type,visited,violations,c.getName());
    }
    private static Set<String> violations(JavaClasses classes) throws ClassNotFoundException {
        var violations = new TreeSet<String>();
        for (var type:classes) {
            String name=type.getName(), from=owner(name);
            boolean entry=name.equals(ROOT+"MolebutterApplication"), migration=name.startsWith("db.migration.");
            if (!name.startsWith(ROOT) && !migration) continue;
            if (entry || migration) {
                if (type.getSource().isEmpty() || !sourceMatches(type.getSource().orElseThrow().getUri(),"app")) violations.add("Wrong module: "+name);
                continue;
            }
            if (!ALLOWED.containsKey(from)) { violations.add("Unknown business package: "+name); continue; }
            String pkg=type.getPackageName();
            boolean persistence=from.equals("common") && pkg.equals(ROOT+"common.persistence");
            if (!(api(pkg,from)||pkg.equals(ROOT+from+".internal")||pkg.startsWith(ROOT+from+".internal.")||persistence))
                violations.add("Outside API/internal packages: "+name);
            if (type.getSource().isEmpty() || !sourceMatches(type.getSource().orElseThrow().getUri(),from.equals("marketplace") && api(pkg,from) ? "marketplace-contracts" : from)) violations.add("Wrong module: "+name);
            if (api(pkg,from)) publicSignatures(Class.forName(name,false,ModuleArchitectureTest.class.getClassLoader()),violations);
            for (var dependency:type.getDirectDependenciesFromSelf()) {
                var target=dependency.getTargetClass();String to=owner(target.getName());
                if (!ALLOWED.containsKey(to)||from.equals(to)) continue;
                if (!ALLOWED.get(from).contains(to)||!(api(target.getPackageName(),to)||to.equals("common")&&target.getPackageName().equals(ROOT+"common.persistence")))
                    violations.add(dependency.getDescription());
            }
        }
        return violations;
    }
    @Test void businessDependenciesUsePublicContractsAndHaveNoCycles() throws Exception {
        var classes=new ClassFileImporter().withImportOption(location -> !location.toString().contains("/test-classes/") && !location.toString().contains("-tests.jar"))
                .importPackages("cc.ataglace.molebutter","db.migration");
        assertThat(violations(classes)).as("Module, package and public signature boundaries").isEmpty();
        slices().matching("cc.ataglace.molebutter.(*)..").should().beFreeOfCycles().check(classes);
    }
    @Test void marketplaceContractsContainNoExecutionOrPersistenceDependencies() {
        var contracts = new ClassFileImporter().withImportOption(location ->
                !location.toString().contains("/test-classes/")).importPackages(ROOT + "marketplace.api");
        assertThat(contracts).isNotEmpty();
        var forbidden = new TreeSet<String>();
        for (var type : contracts) for (var dependency : type.getDirectDependenciesFromSelf()) {
            String target = dependency.getTargetClass().getName();
            if (target.startsWith("org.springframework.") || target.startsWith("java.sql.")
                    || target.startsWith("javax.sql.") || target.startsWith("java.net.http.")
                    || target.startsWith("jakarta.persistence.") || target.contains(".internal."))
                forbidden.add(dependency.getDescription());
        }
        assertThat(forbidden).as("계약 모듈은 실행·저장 구현을 참조하지 않는다").isEmpty();
    }
    @Test void providersDependOnContractsArtifactInsteadOfCommonImplementation() throws Exception {
        var root = java.nio.file.Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (!java.nio.file.Files.isDirectory(root.resolve("molebutter-marketplace-contracts"))) {
            root = root.getParent();
            if (root == null) throw new AssertionError("Reactor root not found");
        }
        for (String provider : List.of("coupang", "naver", "lotteon", "esm")) {
            var pom = java.nio.file.Files.readString(root.resolve("molebutter-marketplace-" + provider + "/pom.xml"));
            assertThat(pom).as(provider).contains("<artifactId>molebutter-marketplace-contracts</artifactId>")
                    .doesNotContain("<artifactId>molebutter-marketplace</artifactId>");
        }
        assertThat(java.nio.file.Files.exists(root.resolve(
                "molebutter-marketplace/src/main/java/cc/ataglace/molebutter/marketplace/api")))
                .as("계약 원본은 한 모듈에서만 관리한다").isFalse();
    }
    @Test void violationsCannotEscapeThroughUnknownPackagesMisplacedModulesOrGenericSignatures() throws Exception {
        var classes=new ClassFileImporter().importClasses(
                cc.ataglace.molebutter.architecturefixture.UnknownBusiness.class,
                cc.ataglace.molebutter.catalog.api.LeakingContractFixture.class);
        assertThat(violations(classes)).anyMatch(v->v.startsWith("Unknown business package:"))
                .anyMatch(v->v.startsWith("Wrong module:"))
                .anyMatch(v->v.startsWith("Internal type in public signature:"));
        var bounds = new TreeSet<String>();
        publicSignatures(cc.ataglace.molebutter.catalog.api.LeakingContractFixture.Bounded.class, bounds);
        assertThat(bounds).anyMatch(v->v.startsWith("Internal type in public signature:"));
    }
}
