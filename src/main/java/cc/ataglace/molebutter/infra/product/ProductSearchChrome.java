package cc.ataglace.molebutter.infra.product;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.function.LongPredicate;
import com.microsoft.playwright.*;

/** at-a-glance의 전용 Chrome 실행 + CDP 연결 방식을 단일 검색 브라우저로 축소했다. */
final class ProductSearchChrome implements AutoCloseable {
    private Process process;
    private Playwright playwright;
    private Browser browser;
    private FileChannel lockFile;
    private FileLock lock;

    BrowserContext open(String executable, Path projectDirectory) {
        if (browser != null && browser.isConnected() && process.isAlive()) return browser.contexts().getFirst();
        close();
        try {
            Path profile = prepareProfile(projectDirectory);
            lockFile = FileChannel.open(profile.resolve("molebutter.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lock = lockFile.tryLock();
            if (lock == null) throw new IllegalStateException("다른 서버가 검색 전용 Chrome을 사용 중입니다.");
            if (Files.exists(profile.resolve("SingletonLock"), LinkOption.NOFOLLOW_LINKS)) {
                recoverStaleLock(profile, InetAddress.getLocalHost().getHostName(),
                    pid -> ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
            }
            Path chrome = Path.of(executable).toAbsolutePath().normalize();
            if (!Files.isExecutable(chrome)) throw new IllegalStateException("설정한 Chrome 실행 파일을 찾을 수 없습니다.");
            // 기존 프로젝트처럼 0이 아닌 포트 번호를 Chrome에 직접 전달한다.
            int port = availablePort();
            process = new ProcessBuilder(command(chrome, profile, port))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            boolean ready = false;
            while (System.nanoTime() < deadline && process.isAlive()) {
                if (endpointReady(port)) { ready = true; break; }
                Thread.sleep(100);
            }
            if (!ready || !process.isAlive())
                throw new IllegalStateException("검색 전용 Chrome 창을 시작하지 못했습니다. Chrome 실행 상태를 확인해 주세요.");
            playwright = Playwright.create(new Playwright.CreateOptions().setEnv(java.util.Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD","1")));
            browser = playwright.chromium().connectOverCDP("http://127.0.0.1:" + port,
                new BrowserType.ConnectOverCDPOptions().setTimeout(10000));
            if (browser.contexts().isEmpty()) throw new IllegalStateException("검색 전용 Chrome 창에 연결하지 못했습니다.");
            return browser.contexts().getFirst();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); close(); throw new IllegalStateException("검색 브라우저 시작이 중단되었습니다.", e);
        } catch (IOException | RuntimeException e) {
            close();
            if (e instanceof IllegalStateException state) throw state;
            throw new IllegalStateException("검색 전용 Chrome을 실행하지 못했습니다.", e);
        }
    }

    static List<String> command(Path executable, Path profile, int port) {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Chrome 연결 포트는 1~65535여야 합니다.");
        return List.of(executable.toString(), "--remote-debugging-address=127.0.0.1", "--remote-debugging-port=" + port,
            "--user-data-dir=" + profile, "--profile-directory=Default", "--disable-extensions",
            "--window-size=1440,900", "--no-first-run", "--no-default-browser-check", "about:blank");
    }

    private static int availablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            return socket.getLocalPort();
        }
    }

    private static boolean endpointReady(int port) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + "/json/version").toURL().openConnection();
            connection.setConnectTimeout(500); connection.setReadTimeout(500);
            return connection.getResponseCode() == 200;
        } catch (IOException e) { return false; }
        finally { if (connection != null) connection.disconnect(); }
    }

    // 개인 프로필이나 이전 로그인용 프로필을 설정으로 지정할 수 없게 경로를 고정한다.
    static Path prepareProfile(Path projectDirectory) throws IOException {
        Path project = projectDirectory.toRealPath();
        Path data = project.resolve("data"), profile = data.resolve("product-search-guest");
        if (Files.isSymbolicLink(data) || Files.isSymbolicLink(profile))
            throw new IllegalStateException("검색 전용 프로필 경로에는 심볼릭 링크를 사용할 수 없습니다.");
        Files.createDirectories(profile);
        if (!profile.toRealPath().equals(profile)) throw new IllegalStateException("검색 전용 프로필 경로를 확인해 주세요.");
        return profile;
    }

    /** 앱의 파일 잠금을 보유한 상태에서, 같은 컴퓨터의 종료된 Chrome 흔적만 정리한다. */
    static void recoverStaleLock(Path profile, String localHostname, LongPredicate isAlive) throws IOException {
        Path singleton = profile.resolve("SingletonLock");
        if (!Files.exists(singleton, LinkOption.NOFOLLOW_LINKS)) return;
        if (!Files.isSymbolicLink(singleton))
            throw new IllegalStateException("검색 전용 Chrome의 잠금 정보를 확인할 수 없습니다. 프로필 상태를 확인해 주세요.");
        Path owner = Files.readSymbolicLink(singleton);
        String value = owner.toString();
        int separator = value.lastIndexOf('-');
        long pid;
        try {
            if (separator < 1 || !localHostname.equalsIgnoreCase(value.substring(0,separator)))
                throw new IllegalArgumentException();
            pid = Long.parseLong(value.substring(separator+1));
            if (pid < 1) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("검색 전용 Chrome 잠금의 소유자를 확인할 수 없어 자동 정리를 중단했습니다.");
        }
        if (isAlive.test(pid))
            throw new IllegalStateException("검색 전용 Chrome 프로세스(PID " + pid + ")가 아직 실행 중입니다. 창이 없어도 백그라운드에 남아 있을 수 있습니다.");
        if (!Files.isSymbolicLink(singleton) || !owner.equals(Files.readSymbolicLink(singleton)))
            throw new IllegalStateException("검색 전용 Chrome 잠금이 변경되었습니다. 다시 조회해 주세요.");
        // 링크 대상(외부 임시 디렉터리)은 따라가지 않으며 쿠키·프로필 데이터도 유지한다.
        for (String name : List.of("SingletonSocket", "SingletonCookie")) {
            Path link = profile.resolve(name);
            if (Files.isSymbolicLink(link)) Files.delete(link);
        }
        Files.delete(singleton);
    }

    @Override public void close() {
        // executor.shutdownNow()의 인터럽트 때문에 정상 종료 대기가 즉시 취소되지 않게 한다.
        boolean interrupted = Thread.interrupted();
        try {
            try { if (playwright != null) playwright.close(); } catch (RuntimeException ignored) { }
            if (process != null && process.isAlive()) {
                process.destroy();
                try {
                    if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                        process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                    }
                } catch (InterruptedException e) { process.destroyForcibly(); interrupted = true; }
            }
        } finally {
            try { if (lock != null) lock.release(); } catch (IOException ignored) { }
            try { if (lockFile != null) lockFile.close(); } catch (IOException ignored) { }
            browser = null; playwright = null; process = null; lock = null; lockFile = null;
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
