package cc.ataglace.molebutter.imaging.internal.job;
import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import cc.ataglace.molebutter.imaging.api.*;
import cc.ataglace.molebutter.imaging.internal.service.DownloadService.PreparedDownload;

class JobRegistryTest {
    private PreparedDownload done(byte[] bytes){return new PreparedDownload(new DownloadResultDto("BAG1.zip",List.of("01.png")),bytes);}
    @Test void exposesTypedResultAndOwnedDefensiveArtifact(){
        var registry=new JobRegistry(Runnable::run,Duration.ofHours(1));byte[] source={1,2};JobDto started=registry.start(1L,()->done(source));
        JobDto result=registry.get(1L,started.id());assertThat(result.status()).isEqualTo(JobDto.STATUS_SUCCEEDED);assertThat(result.result().downloadName()).isEqualTo("BAG1.zip");
        var archive=registry.artifact(1L,started.id());archive.bytes()[0]=9;assertThat(registry.artifact(1L,started.id()).bytes()).containsExactly((byte)1,(byte)2);
        assertThatThrownBy(()->registry.get(2L,started.id())).extracting(e->((ImagingFailure)e).kind()).isEqualTo(ImagingFailure.Kind.NOT_FOUND);
        assertThatThrownBy(()->registry.artifact(2L,started.id())).extracting(e->((ImagingFailure)e).kind()).isEqualTo(ImagingFailure.Kind.NOT_FOUND);
    }
    @Test void keepsBusinessReasonAndHidesUnexpectedExceptionDetails(){
        var registry=new JobRegistry(Runnable::run,Duration.ofHours(1));
        var business=registry.start(1L,()->{throw new ImagingFailure(ImagingFailure.Kind.INVALID_INPUT,"치수를 직접 입력해주세요.");});
        assertThat(registry.get(1L,business.id()).errorMessage()).isEqualTo("치수를 직접 입력해주세요.");
        var unexpected=registry.start(1L,()->{throw new IllegalStateException("/private/server/path credential");});
        assertThat(registry.get(1L,unexpected.id()).errorMessage()).doesNotContain("private","credential");
    }
    @Test void rejectsQueueOverflowWithoutLeakingPendingJobsAndLimitsActor(){
        var queue=new ArrayList<Runnable>();var registry=new JobRegistry(queue::add,Duration.ofHours(1));
        var first=registry.start(1L,()->done(new byte[]{1}));registry.start(1L,()->done(new byte[]{2}));
        assertThatThrownBy(()->registry.start(1L,()->done(new byte[]{3}))).extracting(e->((ImagingFailure)e).kind()).isEqualTo(ImagingFailure.Kind.BUSY);
        assertThatThrownBy(()->registry.artifact(1L,first.id())).extracting(e->((ImagingFailure)e).kind()).isEqualTo(ImagingFailure.Kind.INVALID_INPUT);
        assertThat(registry.start(2L,()->done(new byte[]{4})).status()).isEqualTo(JobDto.STATUS_RUNNING);
        var rejected=new JobRegistry(task->{throw new RejectedExecutionException();},Duration.ofHours(1));
        for(int i=0;i<3;i++)assertThatThrownBy(()->rejected.start(1L,()->done(new byte[]{1}))).hasMessageContaining("대기열");
    }
    @Test void expiresOnReadAtRetentionBoundary(){
        MutableClock clock=new MutableClock();var registry=new JobRegistry(Runnable::run,Duration.ofHours(1),clock);
        var job=registry.start(1L,()->done(new byte[]{1}));clock.now=clock.now.plusSeconds(3600);
        assertThatThrownBy(()->registry.get(1L,job.id())).isInstanceOf(ImagingFailure.class);
        assertThatThrownBy(()->registry.artifact(1L,job.id())).isInstanceOf(ImagingFailure.class);
    }
    @Test void boundsRetainedJobCount(){
        var registry=new JobRegistry(Runnable::run,Duration.ofHours(1));var oldest=registry.start(1L,()->done(new byte[]{1}));
        JobDto latest=null;for(int i=0;i<32;i++)latest=registry.start(1L,()->done(new byte[]{2}));
        assertThatThrownBy(()->registry.get(1L,oldest.id())).isInstanceOf(ImagingFailure.class);assertThat(registry.get(1L,latest.id()).status()).isEqualTo(JobDto.STATUS_SUCCEEDED);
    }
    @Test void boundsRetainedArchiveBytes(){
        var registry=new JobRegistry(Runnable::run,Duration.ofHours(1));byte[] bytes=new byte[32*1024*1024];
        var first=registry.start(1L,()->done(bytes));registry.start(2L,()->done(bytes));var latest=registry.start(3L,()->done(new byte[]{1}));
        assertThatThrownBy(()->registry.get(1L,first.id())).isInstanceOf(ImagingFailure.class);assertThat(registry.get(3L,latest.id()).status()).isEqualTo(JobDto.STATUS_SUCCEEDED);
    }
    static class MutableClock extends Clock{Instant now=Instant.parse("2026-10-08T00:00:00Z");public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now;}}
}
