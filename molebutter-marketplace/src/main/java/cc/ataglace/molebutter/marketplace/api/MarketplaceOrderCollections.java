package cc.ataglace.molebutter.marketplace.api;

import java.util.List;

/** Explicit manual, resumable read-only collection jobs. */
public interface MarketplaceOrderCollections {
    Job start(Long actor, Request request);
    List<Job> list(Long actor);
    Job get(Long actor, String id);
    Job cancel(Long actor, String id);
    Job retry(Long actor, String id);
    record Request(List<String> markets, String dateFrom, String dateTo, String requestId) {
        public Request(List<String> markets,String dateFrom,String dateTo) { this(markets,dateFrom,dateTo,null); }
    }
    record Progress(long orders,long items,long claims,long unknown,long pages,long completedCheckpoints,long totalCheckpoints,long failedDetails,boolean reconstructed) {
        public Progress(long orders,long items,long claims,long unknown,long pages,long completedCheckpoints,long totalCheckpoints,long failedDetails){this(orders,items,claims,unknown,pages,completedCheckpoints,totalCheckpoints,failedDetails,false);}
        public static Progress empty(){return new Progress(0,0,0,0,0,0,0,0);}
    }
    record Failure(String stage,String code,String source,String dateFrom,String dateTo,String orderId,String message) {}
    record CheckpointProgress(String source,String dateFrom,String dateTo,String status,long pages,Failure error) {}
    record MarketResult(String market, String status, long count, String message,Progress progress) {
        public MarketResult(String market,String status,long count,String message){this(market,status,count,message,Progress.empty());}
    }
    record Job(String id, String status, String dateFrom, String dateTo,
               String startedAt, String finishedAt, List<MarketResult> markets,
               String currentStage,Progress progress,List<CheckpointProgress> checkpoints,List<Failure> failures) {
        public Job { markets = List.copyOf(markets);checkpoints=List.copyOf(checkpoints);failures=List.copyOf(failures); }
        public Job(String id,String status,String dateFrom,String dateTo,String startedAt,String finishedAt,List<MarketResult> markets){this(id,status,dateFrom,dateTo,startedAt,finishedAt,markets,status,Progress.empty(),List.of(),List.of());}
    }
}
