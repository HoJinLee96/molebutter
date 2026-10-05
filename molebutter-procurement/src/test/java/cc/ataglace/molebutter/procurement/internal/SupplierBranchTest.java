package cc.ataglace.molebutter.procurement.internal;
import cc.ataglace.molebutter.procurement.api.ProductDtos.*;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

import cc.ataglace.molebutter.procurement.internal.SupplierBranch;

class SupplierBranchTest {
    Offer offer(String title){return new Offer("1",title,"더현대Hi","P","https://hi.thehyundai.com/product/P",1000L,0L);}
    @Test void preservesEvidenceAndSeparatesBranchFromDepartmentStoreName() {
        var branch=SupplierBranch.resolve(offer("[현대백화점 목동점] 가방 ABCD123"),null);
        assertThat(branch.name()).isEqualTo("목동점");assertThat(branch.source()).isEqualTo("네이버 상품명");
        assertThat(branch.evidence()).contains("[현대백화점 목동점]");
        assertThat(SupplierBranch.resolve(offer("백화점 가방"),null).state()).isEqualTo("UNKNOWN");
        assertThat(SupplierBranch.resolve(offer("가방 평점 5점 장점"),null).state()).isEqualTo("UNKNOWN");
    }
    @Test void mainProductStoreIsAvailableEvenWhenSearchTitleOmitsBranch() {
        var info=new SourceDetails("가방","ABCD123","",List.of(),"현대백화점천호점");
        var branch=SupplierBranch.resolve(offer("가방 ABCD123"),info);
        assertThat(branch.name()).isEqualTo("천호점");assertThat(branch.source()).isEqualTo("매입처 상품 정보");
    }
    @Test void contradictionsAreNotSilentlyGuessed() {
        var info=new SourceDetails("[천호점] 가방","","",List.of(),"현대백화점 목동점");
        var branch=SupplierBranch.resolve(offer("가방 ABCD123"),info);
        assertThat(branch.state()).isEqualTo("CONFLICT");assertThat(branch.name()).isNull();assertThat(branch.evidence()).contains("목동점","천호점");
    }
    @Test void repeatedBranchAndHtmlEntitiesAreNormalized() {
        var branch=SupplierBranch.resolve(offer("[<b>목동점</b>] 가방"),new SourceDetails("현대백화점&nbsp;목동점","","",List.of()));
        assertThat(branch.name()).isEqualTo("목동점");assertThat(branch.state()).isEqualTo("CONFIRMED");
    }
}
