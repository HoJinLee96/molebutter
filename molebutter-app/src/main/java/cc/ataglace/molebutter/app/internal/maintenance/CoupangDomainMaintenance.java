package cc.ataglace.molebutter.app.internal.maintenance;

import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.util.*;
import org.springframework.boot.*;
import org.springframework.context.annotation.Configuration;
import db.migration.V46__coupang_domain;

/** Offline domain upgrade with a verified full restore; never resets sales data or starts workers. */
public final class CoupangDomainMaintenance {
    @Configuration(proxyBeanMethods=false) static class Settings {}
    public static void main(String[] args){try{run(args);}catch(Exception failure){System.err.println("쿠팡 전환 중단: "+(failure instanceof IllegalStateException?failure.getMessage():failure.getClass().getSimpleName()+"; 백업을 보존하고 DB·파일 상태를 확인하세요."));System.exit(1);}}
    private static void run(String[] args)throws Exception{
        if(args.length!=2||!args[0].equals("--apply"))throw new IllegalStateException("사용법: --apply <새 백업 디렉터리>");
        var app=new SpringApplication(Settings.class);app.setWebApplicationType(WebApplicationType.NONE);app.setBannerMode(Banner.Mode.OFF);app.setLogStartupInfo(false);app.setDefaultProperties(Map.of("logging.level.root","ERROR"));
        try(var context=app.run()){
            var env=context.getEnvironment();try(var socket=new Socket()){socket.connect(new InetSocketAddress("127.0.0.1",env.getProperty("server.port",Integer.class,8081)),300);throw new IllegalStateException("앱과 워커를 중지한 뒤 전환하세요.");}catch(ConnectException stopped){}
            String url=env.getRequiredProperty("spring.datasource.url"),user=env.getRequiredProperty("spring.datasource.username"),password=env.getRequiredProperty("spring.datasource.password");
            String verify=System.getenv("MOLEBUTTER_UPGRADE_VERIFY_DB_URL"),port=System.getenv("MOLEBUTTER_UPGRADE_VERIFY_PORT");
            if(verify==null||!verify.startsWith("jdbc:mysql://127.0.0.1:")||port==null||!port.matches("[0-9]+"))throw new IllegalStateException("upgrade-coupang-domain.sh의 격리 복원 DB가 필요합니다.");
            var backup=Path.of(args[1]).toAbsolutePath().normalize();Files.createDirectory(backup);
            var previous=Path.of(env.getProperty("coupang.previous-assets-directory","./data/marketplace-assets")).toAbsolutePath().normalize();
            var current=Path.of(env.getProperty("coupang.assets.directory","./data/coupang-assets")).toAbsolutePath().normalize();
            if(previous.equals(current)||backup.startsWith(previous)||backup.startsWith(current)||Files.exists(current)||Files.isSymbolicLink(previous))throw new IllegalStateException("기존·새 이미지 경로와 새 백업 위치를 확인하세요.");
            try(var connection=DriverManager.getConnection(url,user,password)){
                var old=SalesSchema.existing(connection);if(old.size()!=32)throw new IllegalStateException("V45의 판매 테이블 32개가 필요합니다.");
                try(var st=connection.createStatement();var r=st.executeQuery("SELECT COUNT(*) FROM marketplace_asset")){r.next();if(r.getLong(1)>0&&!Files.isDirectory(previous))throw new IllegalStateException("기존 판매 이미지 경로가 없습니다.");}
                var before=V46__coupang_domain.snapshot(connection);var full=SalesDataMaintenance.digests(connection);
                var uri=URI.create(url.substring(5));String database=uri.getPath().substring(1);if(!database.matches("[A-Za-z0-9_]+"))throw new IllegalStateException("DB 이름을 확인하세요.");
                var dump=backup.resolve("database.sql");
                process(List.of("mysqldump","--single-transaction","--hex-blob","--no-tablespaces","--skip-add-locks","--set-gtid-purged=OFF","--host="+uri.getHost(),"--port="+(uri.getPort()<0?3306:uri.getPort()),"--user="+user,database),password,null,dump,backup.resolve("dump-error.log"));
                process(List.of("mysql","--no-defaults","--binary-mode","--host=127.0.0.1","--port="+port,"--user=root","coupang_upgrade_verify"),"",dump,backup.resolve("restore-output.log"),backup.resolve("restore-error.log"));
                try(var restored=DriverManager.getConnection(verify,"root","")){if(!full.equals(SalesDataMaintenance.digests(restored)))throw new IllegalStateException("백업 복원 대조에 실패했습니다. 데이터를 변경하지 않았습니다.");}
                var files=fileHashes(previous);if(Files.exists(previous))copyTree(previous,backup.resolve("sales-assets"));
                if(!files.equals(fileHashes(backup.resolve("sales-assets"))))throw new IllegalStateException("이미지 백업 대조에 실패했습니다.");
                Files.writeString(backup.resolve("verified-before.txt"),full.toString());Files.writeString(backup.resolve("canonical-before.txt"),before.toString());Files.writeString(backup.resolve("asset-locations.txt"),previous+"\n"+current+"\n");
                if(!before.equals(V46__coupang_domain.snapshot(connection))||!files.equals(fileHashes(previous)))throw new IllegalStateException("백업 중 데이터가 변경되었습니다.");
                org.flywaydb.core.Flyway.configure().dataSource(env.getProperty("spring.flyway.url",url),env.getProperty("spring.flyway.user",user),env.getProperty("spring.flyway.password",password)).target("46").load().migrate();
                if(!before.equals(V46__coupang_domain.snapshot(connection)))throw new IllegalStateException("전환 후 행·참조 데이터가 일치하지 않습니다. 재기동하지 말고 백업을 보존하세요.");
                if(Files.exists(previous)){Files.createDirectories(current.getParent());Files.move(previous,current,StandardCopyOption.ATOMIC_MOVE);}else Files.createDirectories(current);
                if(!files.equals(fileHashes(current)))throw new IllegalStateException("전환 후 이미지 데이터가 일치하지 않습니다.");
                Files.writeString(backup.resolve("UPGRADE_COMPLETE"),"V46 applied; all business rows, immutable evidence, IDs and asset bytes preserved\n");
                System.out.println("쿠팡 V46 전환 완료. 전체 DB 격리 복원·행 지문·이미지 바이트 동일성 확인. 백업: "+backup);
            }
        }
    }
    private static void process(List<String> command,String password,Path input,Path output,Path error)throws Exception{
        var builder=new ProcessBuilder(command);builder.environment().put("MYSQL_PWD",password);if(input!=null)builder.redirectInput(input.toFile());builder.redirectOutput(output.toFile());builder.redirectError(error.toFile());if(builder.start().waitFor()!=0)throw new IllegalStateException("DB 백업 또는 격리 복원 명령 실패. 백업을 보존하세요.");
    }
    private static Map<String,String> fileHashes(Path directory)throws Exception{
        var result=new TreeMap<String,String>();if(!Files.exists(directory))return result;
        try(var paths=Files.walk(directory)){for(var path:paths.toList()){if(Files.isSymbolicLink(path))throw new IllegalStateException("판매 이미지 디렉터리에 심볼릭 링크가 있습니다.");if(Files.isRegularFile(path))result.put(directory.relativize(path).toString(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));}}
        return result;
    }
    private static void copyTree(Path from,Path to)throws Exception{try(var paths=Files.walk(from)){for(var path:paths.toList()){var destination=to.resolve(from.relativize(path));if(Files.isDirectory(path))Files.createDirectories(destination);else Files.copy(path,destination,StandardCopyOption.COPY_ATTRIBUTES);}}}
}
