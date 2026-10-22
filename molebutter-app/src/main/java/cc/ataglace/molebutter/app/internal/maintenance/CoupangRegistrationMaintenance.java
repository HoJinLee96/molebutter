package cc.ataglace.molebutter.app.internal.maintenance;
import java.sql.*;
import java.util.*;
import org.springframework.boot.*;
/** Verified offline additive V51 rollout; never starts HTTP or collection workers. */
public final class CoupangRegistrationMaintenance {
    public static void main(String[] args){
        if (args.length != 1 || !args[0].equals("--status")) {
            CoupangWorkingCopyMaintenance.main(args,51); return;
        }
        var app = new SpringApplication(CoupangWorkingCopyMaintenance.Settings.class);
        app.setWebApplicationType(WebApplicationType.NONE); app.setBannerMode(Banner.Mode.OFF);
        app.setLogStartupInfo(false); app.setDefaultProperties(Map.of("logging.level.root","ERROR"));
        try (var context = app.run()) {
            var env = context.getEnvironment();
            try (var c = DriverManager.getConnection(env.getRequiredProperty("spring.datasource.url"),
                    env.getRequiredProperty("spring.datasource.username"),env.getRequiredProperty("spring.datasource.password"));
                    var s = c.createStatement()) {
                try (var rows = s.executeQuery("SELECT version,success FROM flyway_schema_history WHERE version IN ('50','51') ORDER BY installed_rank")) {
                    while(rows.next()) System.out.println("V"+rows.getString(1)+" success="+rows.getBoolean(2));
                }
                var existing=CoupangSchema.existing(c);
                System.out.println("coupang_registration_defaults exists="+existing.contains("coupang_registration_defaults"));

            }
        } catch (Exception failure) {
            System.err.println("V51 상태 확인 중단: "+(failure instanceof IllegalStateException ? failure.getMessage() : failure.getClass().getSimpleName()));
            System.exit(1);
        }
    }
}
