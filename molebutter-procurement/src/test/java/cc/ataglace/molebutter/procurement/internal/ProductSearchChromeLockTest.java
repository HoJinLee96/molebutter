package cc.ataglace.molebutter.procurement.internal;
import cc.ataglace.molebutter.procurement.internal.ProductSearchChrome;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

class ProductSearchChromeLockTest {
    @TempDir Path profile;

    @Test void removesDeadOwnersLinksWithoutTouchingTheirTargetsOrProfileData() throws Exception {
        Path target=Files.createTempFile(profile,"socket-target-",".txt");
        Files.writeString(target,"retain");
        Files.createSymbolicLink(profile.resolve("SingletonLock"),Path.of("my-mac.local-60553"));
        Files.createSymbolicLink(profile.resolve("SingletonSocket"),target);
        Files.createSymbolicLink(profile.resolve("SingletonCookie"),Path.of("3474944"));
        Path cookies=Files.createDirectories(profile.resolve("Default")).resolve("Cookies");
        Files.writeString(cookies,"existing-profile");
        ProductSearchChrome.recoverStaleLock(profile,"my-mac.local",pid->{assertThat(pid).isEqualTo(60553);return false;});
        for(String name:new String[]{"SingletonLock","SingletonSocket","SingletonCookie"})
            assertThat(Files.exists(profile.resolve(name),LinkOption.NOFOLLOW_LINKS)).isFalse();
        assertThat(Files.readString(target)).isEqualTo("retain");
        assertThat(Files.readString(cookies)).isEqualTo("existing-profile");
    }

    @Test void leavesLivingOwnersLockUntouched() throws Exception {
        Path lock=profile.resolve("SingletonLock");
        Files.createSymbolicLink(lock,Path.of("my-mac.local-123"));
        assertThatThrownBy(()->ProductSearchChrome.recoverStaleLock(profile,"my-mac.local",pid->true))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("백그라운드");
        assertThat(Files.readSymbolicLink(lock).toString()).isEqualTo("my-mac.local-123");
    }

    @Test void neverDeletesUnknownOrForeignOwnerLocks() throws Exception {
        Path lock=profile.resolve("SingletonLock");
        for(String owner:new String[]{"other-mac.local-123","my-mac.local-unknown","my-mac.local-0","invalid"}) {
            Files.createSymbolicLink(lock,Path.of(owner));
            assertThatThrownBy(()->ProductSearchChrome.recoverStaleLock(profile,"my-mac.local",pid->false))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("소유자");
            assertThat(Files.readSymbolicLink(lock).toString()).isEqualTo(owner);
            Files.delete(lock);
        }
        Files.writeString(lock,"unexpected");
        assertThatThrownBy(()->ProductSearchChrome.recoverStaleLock(profile,"my-mac.local",pid->false))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("잠금 정보");
        assertThat(Files.readString(lock)).isEqualTo("unexpected");
    }

    @Test void proceedsWithoutALockAndDoesNotGuessOwnership() throws Exception {
        ProductSearchChrome.recoverStaleLock(profile,"my-mac.local",pid->{throw new AssertionError("No owner to inspect");});
    }

    @Test void doesNotRemoveALockWhoseOwnerChangedDuringInspection() throws Exception {
        Path lock=profile.resolve("SingletonLock");
        Files.createSymbolicLink(lock,Path.of("my-mac.local-123"));
        assertThatThrownBy(()->ProductSearchChrome.recoverStaleLock(profile,"my-mac.local",pid->{
            try {Files.delete(lock);Files.createSymbolicLink(lock,Path.of("my-mac.local-456"));}
            catch(Exception e) {throw new RuntimeException(e);}
            return false;
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("변경");
        assertThat(Files.readSymbolicLink(lock).toString()).isEqualTo("my-mac.local-456");
    }

    @Test void shutdownWaitsForTheOwnedProcessEvenWhenTheWorkerWasInterrupted() throws Exception {
        Process child=new ProcessBuilder("/bin/sleep","30").start();
        ProductSearchChrome chrome=new ProductSearchChrome();
        ReflectionTestUtils.setField(chrome,"process",child);
        try {
            Thread.currentThread().interrupt();
            chrome.close();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(child.isAlive()).isFalse();
        } finally {Thread.interrupted();child.destroyForcibly();}
    }
}
