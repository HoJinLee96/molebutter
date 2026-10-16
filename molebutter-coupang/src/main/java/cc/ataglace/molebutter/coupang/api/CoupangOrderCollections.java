package cc.ataglace.molebutter.coupang.api;

import java.util.List;

/** Explicit manual, resumable read-only collection jobs. */
public interface CoupangOrderCollections {
    Job start(Long actor, Request request);
    List<Job> list(Long actor);
    Job get(Long actor, String id);
    Job cancel(Long actor, String id);
    Job retry(Long actor, String id);
    record Request(String dateFrom,String dateTo,String requestId) {
        public Request(String dateFrom,String dateTo){this(dateFrom,dateTo,null);}
    }
    record Progress(long orders,long items,long claims,long unknown,long pages,long completedCheckpoints,long totalCheckpoints,long failedDetails,boolean reconstructed) {
        public Progress(long orders,long items,long claims,long unknown,long pages,long completedCheckpoints,long totalCheckpoints,long failedDetails){this(orders,items,claims,unknown,pages,completedCheckpoints,totalCheckpoints,failedDetails,false);}
        public static Progress empty(){return new Progress(0,0,0,0,0,0,0,0);}
    }
    record Failure(String stage,String code,String source,String dateFrom,String dateTo,String orderId,String message) {}
    record CheckpointProgress(String source,String dateFrom,String dateTo,String status,long pages,Failure error) {}
    record Result(String status,long count,String message,Progress progress) {}
    record Job(String id,String status,String dateFrom,String dateTo,String startedAt,String finishedAt,
               Result result,String currentStage,Progress progress,List<CheckpointProgress> checkpoints,List<Failure> failures) {
        public Job { checkpoints=List.copyOf(checkpoints);failures=List.copyOf(failures); }
    }
}
