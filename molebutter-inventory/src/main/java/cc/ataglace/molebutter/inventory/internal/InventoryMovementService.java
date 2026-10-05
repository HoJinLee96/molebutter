package cc.ataglace.molebutter.inventory.internal;
import cc.ataglace.molebutter.inventory.api.InventoryDtos.*;
import cc.ataglace.molebutter.inventory.internal.InventoryQueryService;
import cc.ataglace.molebutter.inventory.internal.InventorySupport;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import cc.ataglace.molebutter.common.api.ErrorCode;
import cc.ataglace.molebutter.common.api.OperationFailure;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import cc.ataglace.molebutter.common.api.BusinessException;
import cc.ataglace.molebutter.catalog.api.CatalogConsistencyGuard;
import cc.ataglace.molebutter.common.api.BusinessRevision;
import cc.ataglace.molebutter.common.api.BusinessTime;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
import cc.ataglace.molebutter.common.api.BusinessIds;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.time.LocalDateTime;

@Service
@Transactional(readOnly=true)
public class InventoryMovementService extends InventorySupport {
    private final InventoryQueryService queries;
    public InventoryMovementService(JdbcTemplate jdbc, BusinessAccess access, CatalogConsistencyGuard guard, BusinessTime time, ObjectMapper json, InventoryQueryService queries) {
        super(jdbc,access,guard,time,json);
        this.queries=queries;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> move(Long actor,long item,String requestId,MovementInput input) {
        boolean a=admin(actor);guard.shared();String req=request(requestId),digest=movementHash(actor,item,input);requestLock(req);
        var exists=jdbc.queryForList("SELECT * FROM inventory_movement WHERE request_id=?",req);
        if(!exists.isEmpty()){if(!digest.equals(exists.getFirst().get("request_hash")))throw new OperationFailure("동일 요청 식별자의 내용이 다릅니다.");return queries.item(actor,item);}
        if(input.kind()==Kind.CANCEL_PENDING&&!a)throw new BusinessException(ErrorCode.HANDLE_ACCESS_DENIED);
        var row=rawItem(item);version(row,input.revision());apply(actor,row,req,digest,input);return queries.item(actor,item);
    }
    private String movementHash(Long actor,long item,MovementInput input) {
        // Retired location is used only in the original fingerprint so historical requests can be retried.
        var fields=new LinkedHashMap<String,Object>();fields.put("revision",input.revision());fields.put("kind",input.kind());fields.put("quantity",input.quantity());fields.put("occurredAt",input.occurredAt());fields.put("reason",input.reason());fields.put("referenceNumber",input.referenceNumber());fields.put("referenceId",input.referenceId());
        if(input.location()!=null)fields.put("location",input.location());
        return hash(List.of(actor,item,fields));
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void apply(Long actor,Map<String,Object> item,String req,String digest,MovementInput x) {
        Kind kind=required(x.kind(),"입출고 유형을 선택해 주세요.");long qty=quantity(x.quantity()),itemId=number(item,"id");
        required(x.occurredAt(),"발생일을 입력해 주세요.");String reason=text(x.reason(),1000);
        if(Set.of(Kind.ADJUST_IN,Kind.ADJUST_OUT,Kind.REVERSE,Kind.DISPOSE).contains(kind)&&reason.isBlank())throw new InputValidationFailure("정정·취소·폐기 사유를 입력해 주세요.");
        long dh=0,dp=0;Long reference=id(x.referenceId()),reverses=null;
        switch(kind) {
            case RECEIPT -> {dh=qty;dp=-qty;}
            case CANCEL_PENDING -> dp=-qty;
            case SALE_OUT,SUPPLIER_RETURN,DISPOSE,ADJUST_OUT -> dh=-qty;
            case ADJUST_IN -> dh=qty;
            case CUSTOMER_RETURN -> {
                required(reference,"원래 판매 출고를 선택해 주세요.");var origin=one("SELECT * FROM inventory_movement WHERE id=? AND item_id=?",reference,itemId);
                if(!"SALE_OUT".equals(origin.get("kind"))||isReversed(reference))throw new OperationFailure("유효한 판매 출고를 선택해 주세요.");
                long returned=returned(reference);
                if(qty>number(origin,"quantity")-returned)throw new OperationFailure("원출고의 남은 반품 가능 수량을 초과했습니다.");dh=qty;
            }
            case REVERSE -> {
                required(reference,"취소할 원래 기록을 선택해 주세요.");var origin=one("SELECT * FROM inventory_movement WHERE id=? AND item_id=?",reference,itemId);
                if(isReversed(reference)||Set.of("REVERSE","ORDER_INCREASE","ORDER_DECREASE").contains(origin.get("kind")))throw new OperationFailure("취소할 수 없는 기록입니다.");
                if("CANCEL_PENDING".equals(origin.get("kind")))access.productActor(actor,true);
                if("SALE_OUT".equals(origin.get("kind"))&&returned(reference)>0)throw new OperationFailure("연결된 고객 반품을 먼저 취소해 주세요.");
                if(!"NONE".equals(origin.get("refund_status")))throw new OperationFailure("환불 기록을 먼저 해제해 주세요.");
                if(qty!=number(origin,"quantity"))throw new InputValidationFailure("기록 취소는 원래 수량 전체로 처리합니다.");
                dh=-number(origin,"hand_delta");dp=-number(origin,"pending_delta");reverses=reference;
            }
        }
        if(kind!=Kind.CUSTOMER_RETURN&&kind!=Kind.REVERSE&&reference!=null)throw new InputValidationFailure("이 유형에는 원래 기록을 연결할 수 없습니다.");
        long hand=number(item,"on_hand")+dh,pending=number(item,"pending")+dp;
        if(hand<0||hand>MAX_QUANTITY||pending<0||pending>number(item,"ordered_quantity"))throw new OperationFailure("보유·미입고 수량 범위를 벗어납니다. 관련 기록과 수량을 확인해 주세요.");
        if(dh>0||dp>0)activeItemProduct(item);
        record(actor,itemId,req,digest,kind.name(),qty,dh,dp,x.occurredAt(),reason,text(x.referenceNumber(),255),reference,reverses);
        jdbc.update("UPDATE inventory_item SET on_hand=?,pending=?,revision=revision+1,updated_at=? WHERE id=?",hand,pending,time.now(),itemId);
    }
    private boolean isReversed(long movement){return jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movement WHERE reverses_id=?",Long.class,movement)>0;}
    private long returned(long sale){return jdbc.queryForObject("SELECT COALESCE(SUM(m.quantity),0) FROM inventory_movement m WHERE m.reference_id=? AND m.kind='CUSTOMER_RETURN' AND NOT EXISTS(SELECT 1 FROM inventory_movement r WHERE r.reverses_id=m.id)",Long.class,sale);}
    @Transactional(propagation=Propagation.MANDATORY)
    public void record(Long actor,long item,String req,String digest,String kind,long qty,long dh,long dp,LocalDateTime at,String reason,String refNumber,Long reference,Long reverses) {
        jdbc.update("INSERT INTO inventory_movement(id,item_id,kind,quantity,hand_delta,pending_delta,occurred_at,reason,reference_number,reference_id,reverses_id,actor_id,request_id,request_hash,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",BusinessIds.next(),item,kind,qty,dh,dp,at,reason,refNumber,reference,reverses,actor,req,digest,time.now());
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> refund(Long actor,long movement,RefundInput x) {
        access.productActor(actor,true);guard.shared();var link=one("SELECT item_id FROM inventory_movement WHERE id=?",movement);
        rawItem(number(link,"item_id"));
        var r=one("SELECT * FROM inventory_movement WHERE id=? FOR UPDATE",movement);
        BusinessRevision.check(number(r,"refund_revision"),x.revision());
        if(!"SUPPLIER_RETURN".equals(r.get("kind"))||isReversed(movement))throw new OperationFailure("유효한 매입 반품 기록에서 환불을 관리해 주세요.");
        if(!Set.of("NONE","PENDING","COMPLETED").contains(Objects.toString(x.status(),"")))throw new InputValidationFailure("환불 상태를 확인해 주세요.");
        if(x.amount()!=null&&(x.amount()<0||x.amount()>1_000_000_000_000_000L))throw new InputValidationFailure("환불액을 확인해 주세요.");
        if("COMPLETED".equals(x.status())){required(x.amount(),"환불액을 입력해 주세요.");required(x.refundedOn(),"환불일을 입력해 주세요.");}
        if(!"COMPLETED".equals(x.status())&&x.refundedOn()!=null)throw new InputValidationFailure("환불 완료 시에만 환불일을 입력합니다.");
        jdbc.update("UPDATE inventory_movement SET refund_status=?,refund_amount=?,refunded_on=?,refund_revision=refund_revision+1 WHERE id=?",x.status(),"NONE".equals(x.status())?null:x.amount(),x.refundedOn(),movement);
        return queries.movement(actor,movement);
    }
}
