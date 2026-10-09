package cc.ataglace.molebutter.media.internal;

import cc.ataglace.molebutter.common.api.InputValidationFailure;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ImageAssetReferencesTest {
    private static final String A="00000000-0000-4000-8000-000000000001";
    private static final String B="00000000-0000-4000-8000-000000000002";
    private static final String LOCK="SELECT id FROM marketplace_asset WHERE id=? FOR UPDATE";
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final LocalImageAssets assets=new LocalImageAssets(db,mock(BusinessAccess.class),
        mock(PlatformTransactionManager.class),"./data/marketplace-assets","");

    @Test void pinsInStableLockOrderAndProtectsSubmittedAssets() {
        for(String id:List.of(A,B)){
            when(db.queryForList(LOCK,String.class,id)).thenReturn(List.of(id));
            when(db.queryForObject("SELECT COUNT(*) FROM marketplace_draft_asset WHERE draft_id=? AND asset_id=?",Long.class,10L,id)).thenReturn(1L);
        }
        assets.pinReferences(1L,20L,"10",Set.of(B,A));
        var order=inOrder(db);
        order.verify(db).queryForList(LOCK,String.class,A);
        order.verify(db).update("INSERT INTO marketplace_execution_asset(submission_id,asset_id) VALUES(?,?)",20L,A);
        order.verify(db).update("UPDATE marketplace_asset SET pending_delete_at=NULL WHERE id=?",A);
        order.verify(db).queryForList(LOCK,String.class,B);
        order.verify(db).update("INSERT INTO marketplace_execution_asset(submission_id,asset_id) VALUES(?,?)",20L,B);
        verify(db,never()).queryForObject("SELECT COUNT(*) FROM marketplace_asset WHERE id=? AND created_by=?",Long.class,A,1L);
    }

    @Test void submittedUnsavedAssetsMustBelongToActor() {
        when(db.queryForList(LOCK,String.class,A)).thenReturn(List.of(A));
        when(db.queryForObject("SELECT COUNT(*) FROM marketplace_draft_asset WHERE draft_id=? AND asset_id=?",Long.class,10L,A)).thenReturn(0L);
        when(db.queryForObject("SELECT COUNT(*) FROM marketplace_asset WHERE id=? AND created_by=?",Long.class,A,1L)).thenReturn(0L);
        assertThatThrownBy(()->assets.pinReferences(1L,20L,"10",Set.of(A))).isInstanceOf(InputValidationFailure.class);
        verify(db,never()).update("INSERT INTO marketplace_execution_asset(submission_id,asset_id) VALUES(?,?)",20L,A);
    }

    @Test void actorMayPinTheirOwnPendingUploadWithoutACommonDraftReference() {
        when(db.queryForList(LOCK,String.class,A)).thenReturn(List.of(A));
        when(db.queryForObject("SELECT COUNT(*) FROM marketplace_draft_asset WHERE draft_id=? AND asset_id=?",Long.class,10L,A)).thenReturn(0L);
        when(db.queryForObject("SELECT COUNT(*) FROM marketplace_asset WHERE id=? AND created_by=?",Long.class,A,1L)).thenReturn(1L);
        assets.pinReferences(1L,20L,"10",Set.of(A));
        verify(db).update("INSERT INTO marketplace_execution_asset(submission_id,asset_id) VALUES(?,?)",20L,A);
    }

    @Test void missingOrInvalidAssetCannotBePinned() {
        assertThatThrownBy(()->assets.pinReferences(1L,20L,"10",Set.of("../image"))).isInstanceOf(InputValidationFailure.class);
        verifyNoInteractions(db);
        when(db.queryForList(LOCK,String.class,A)).thenReturn(List.of());
        assertThatThrownBy(()->assets.pinReferences(1L,20L,"10",Set.of(A))).isInstanceOf(InputValidationFailure.class);
    }

    @Test void releaseLocksBeforeRemovingPinsAndKeepsOtherOwnersProtected() {
        when(db.queryForList("SELECT asset_id FROM marketplace_execution_asset WHERE submission_id=? ORDER BY asset_id",String.class,20L)).thenReturn(List.of(A,B));
        assets.releaseReferences(20L);
        var order=inOrder(db);
        order.verify(db).queryForList(LOCK,String.class,A);
        order.verify(db).queryForList(LOCK,String.class,B);
        order.verify(db).update("DELETE FROM marketplace_execution_asset WHERE submission_id=?",20L);
        for(String id:List.of(A,B))order.verify(db).update("UPDATE marketplace_asset a SET pending_delete_at=COALESCE(pending_delete_at,CURRENT_TIMESTAMP(6)) WHERE id=? AND NOT EXISTS(SELECT 1 FROM marketplace_draft_asset r WHERE r.asset_id=a.id) AND NOT EXISTS(SELECT 1 FROM marketplace_execution_asset r WHERE r.asset_id=a.id)",id);
        verify(db,never()).update("DELETE FROM marketplace_asset WHERE id=?",A);
    }
}
