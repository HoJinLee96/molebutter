package cc.ataglace.molebutter.app.internal;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cc.ataglace.molebutter.coupang.api.*;
import cc.ataglace.molebutter.imaging.api.*;
import cc.ataglace.molebutter.media.api.*;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
class CoupangPrefillServiceTest {
    final BusinessAccess access=mock(BusinessAccess.class);final ProductImageWorkspace workspace=mock(ProductImageWorkspace.class);final ProductImageUploadService uploads=mock(ProductImageUploadService.class);
    final CoupangWorkingCopies copies=mock(CoupangWorkingCopies.class);final CoupangEditor editor=mock(CoupangEditor.class);final CoupangBrands brands=mock(CoupangBrands.class);final CoupangPricing pricing=mock(CoupangPricing.class);final CoupangRecommendationSources sources=mock(CoupangRecommendationSources.class);final CoupangRegistrationDefaults defaults=mock(CoupangRegistrationDefaults.class);final CoupangWriteGateway writer=mock(CoupangWriteGateway.class);final R2Images r2=mock(R2Images.class);
    final MutableClock clock=new MutableClock();CoupangPrefillService service;String account="a".repeat(64);
    @BeforeEach void setup(){service=new CoupangPrefillService(access,workspace,uploads,copies,editor,brands,pricing,sources,defaults,writer,r2,clock,new cc.ataglace.molebutter.coupang.internal.DefaultCoupangSearchTags());when(writer.accountKey()).thenAnswer(c->account);
        when(workspace.observed(1L,"SOURCE1","DAKS")).thenReturn(new ProductLookupDto("SOURCE1","DAKS","닥스","닥스 지갑",200000,150000,List.of("https://source/1.jpg"),List.of(),Map.of("소재","소가죽","제조사(공식수입/병행수입)","LF"),"FREE",null,Map.of(),false,null,null,null,"지갑"));
        when(defaults.get(1L)).thenReturn(new CoupangRegistrationDefaults.Settings(account,0,Map.of(),Map.of(),Map.of()));
        when(sources.byCodes(1L,Set.of("SKU123"))).thenReturn(Map.of());when(uploads.recent(anyLong(),anyString(),anyString(),anyString())).thenReturn(Optional.empty());
        when(editor.predict(anyLong(),anyString(),anyString(),anyString())).thenReturn(new CoupangEditor.Prediction("SUCCESS","123","지갑"));when(editor.validCategory(1L,"123")).thenReturn(true);
        when(editor.category(1L,"123")).thenReturn(new CoupangEditor.CategoryRules("123",true,List.of(new CoupangEditor.AttributeRule("Manufacturer Part Number","NONE","OPTIONAL",null,"STRING",null,List.of())),List.of(new CoupangEditor.NoticeCategory("패션잡화",List.of(new CoupangEditor.NoticeRule("소재","MANDATORY"),new CoupangEditor.NoticeRule("제조자(수입자)","MANDATORY")))),List.of(),List.of(),List.of("NEW")));
        when(brands.search(1L,"닥스",1,null)).thenReturn(new CoupangBrands.Page(List.of(new CoupangBrands.Brand("42","닥스",false,List.of())),1,1,false));
        when(r2.list(1L,"SKU123",null)).thenReturn(new R2Images.Page("SKU123",List.of(),null,List.of()));
        when(copies.prefill(eq(1L),any())).thenAnswer(c->new CoupangWorkingCopies.Entry("CREATE",null,null,null,c.getArgument(1),"session",clock.instant().plusSeconds(600).toString(),null,false,List.of(),account,null,null,null));
    }
    CoupangPrefillService.Request request(){return new CoupangPrefillService.Request("SOURCE1","DAKS"," sku 123 ",List.of());}
    R2Images.Entry file(String name){String key="products/SKU123/"+name;return new R2Images.Entry(key,"https://assets.test/"+key,"",900,clock.instant());}
    void files(String...names){var entries=Arrays.stream(names).map(this::file).toList();when(r2.list(1L,"SKU123",null)).thenReturn(new R2Images.Page("SKU123",entries,null,List.of()));for(var e:entries){var image=new R2Images.Image(e.key(),e.url(),"image/png",750,962,900);when(r2.resolve(1L,e.url())).thenReturn(image);when(r2.read(1L,e.url())).thenReturn(new R2Images.Content(image,new byte[]{1}));}}
    @Test void onlyOwnedServerObservationIsMappedAndPreparationNeverSavesOrUploads(){var result=service.prepare(1L,request());var d=result.entry().document();assertThat(d.basic()).containsEntry("sellerProductName","닥스 소가죽 지갑 SKU123").containsEntry("brand","닥스").containsEntry("displayCategoryCode","123").containsEntry("displayProductName","닥스 소가죽 지갑").doesNotContainKey("generalProductName");assertThat(d.settings()).containsEntry("manufacture","LF").containsEntry("saleStartedAt","2026-10-20T13:00:00").containsEntry("saleEndedAt","2099-12-31T23:59:00");var o=d.options().getFirst();assertThat(o.registration()).containsEntry("externalVendorSku","SKU123").containsEntry("originalPrice","200000").doesNotContainKeys("salePrice","maximumBuyCount");assertThat(o.attributes()).contains(new CoupangCatalog.Attribute("Manufacturer Part Number","SOURCE1","NONE"));assertThat(o.notices()).contains(new CoupangCatalog.Notice("패션잡화","제조자(수입자)","LF"));assertThat(o.notices()).noneMatch(n->n.content().equals("상품 상세페이지 참조"));verify(copies,never()).save(any(),any(),any());verify(uploads,never()).start(any(),any());assertThat(service.get(1L,result.token())).isSameAs(result);}
    @Test void naturalFileOrderAndWholeDescriptionDoNotUseSizeAsRepresentative(){files("processed/상품정보.png","official/10.png","official/02.jpg","processed/사이즈.png","official/01.jpg");var r=service.prepare(1L,request());var o=r.entry().document().options().getFirst();assertThat(o.images()).extracting(CoupangWorkingCopies.Image::url).containsExactly(file("official/01.jpg").url(),file("official/02.jpg").url(),file("official/10.png").url());assertThat(o.contents()).extracting(CoupangWorkingCopies.Content::content).containsExactly(file("official/01.jpg").url(),file("official/02.jpg").url(),file("official/10.png").url(),file("processed/사이즈.png").url(),file("processed/상품정보.png").url());assertThat(r.notes()).anyMatch(n->n.source().equals("경고"));files("processed/사이즈.png");assertThat(service.prepare(1L,request()).entry().document().options().getFirst().images()).isEmpty();}
    @Test void tenPhotoLimitAndDuplicateNumberAreExplicit(){files(java.util.stream.IntStream.rangeClosed(1,12).mapToObj(i->"official/%02d.jpg".formatted(i)).toArray(String[]::new));var r=service.prepare(1L,request());assertThat(r.entry().document().options().getFirst().images()).hasSize(10);assertThat(r.entry().document().options().getFirst().contents()).hasSize(12);assertThat(r.notes()).anyMatch(n->n.message().contains("2개 사진"));files("official/01.jpg","official/01.png","official/02.jpg");r=service.prepare(1L,request());assertThat(r.entry().document().options().getFirst().images()).hasSize(1);assertThat(r.notes()).anyMatch(n->n.source().equals("중복 번호"));}
    @Test void inaccessibleAndWrongProductNeverBecomeNotFound(){files("official/01.jpg","official/02.jpg");when(r2.resolve(1L,file("official/01.jpg").url())).thenThrow(new InputValidationFailure("권한 오류"));var r=service.prepare(1L,request());assertThat(r.entry().document().options().getFirst().images()).hasSize(1);assertThat(r.notes()).anyMatch(n->n.field().startsWith("이미지 파일 확인")&&n.source().equals("조회 실패"));when(r2.list(1L,"SKU123",null)).thenThrow(new InputValidationFailure("403"));assertThat(service.prepare(1L,request()).notes()).anyMatch(n->n.message().contains("파일 없음으로 판단하지"));}
    @Test void exactRecentUploadUsesCurrentOrderAndDoesNotInventMissingGeneratedFile(){files("official/01.jpg","official/02.jpg");var a=new DownloadImageItemDto(0,null,"https://source/1.jpg");var b=new DownloadImageItemDto(1,null,"https://source/2.jpg");var size=new DownloadImageItemDto(null,"generated-size",null);when(uploads.matchesRecent(anyLong(),anyString(),anyString(),anyString(),anyString(),any())).thenReturn(true);when(uploads.recent(1L,"SOURCE1","DAKS","SKU123")).thenReturn(Optional.of(Map.of(a,new ProductImageUploadJob.File("01.jpg",file("official/01.jpg").key(),file("official/01.jpg").url()),b,new ProductImageUploadJob.File("02.jpg",file("official/02.jpg").key(),file("official/02.jpg").url()))));var r=service.prepare(1L,new CoupangPrefillService.Request("SOURCE1","DAKS","SKU123",List.of(b,a,size)));assertThat(r.entry().document().options().getFirst().images()).extracting(CoupangWorkingCopies.Image::url).containsExactly(file("official/02.jpg").url(),file("official/01.jpg").url());assertThat(r.notes()).anyMatch(n->n.source().equals("미업로드·삭제"));verify(workspace).prepareExport(eq(1L),any());}
    @Test void ambiguousBrandCategoryAndMultiOptionStockStayManual(){when(brands.search(1L,"닥스",1,null)).thenReturn(new CoupangBrands.Page(List.of(new CoupangBrands.Brand("1","닥스",false,List.of()),new CoupangBrands.Brand("2","닥스",false,List.of())),1,2,false));when(editor.validCategory(1L,"123")).thenReturn(false);when(sources.byCodes(1L,Set.of("SKU123"))).thenReturn(Map.of("SKU123",new CoupangRecommendationSources.Source("7",30000L,true,"2026-10-19",List.of(new CoupangRecommendationSources.Stock("a","블랙",2,"AVAILABLE"),new CoupangRecommendationSources.Stock("b","브라운",3,"AVAILABLE")),2,"AVAILABLE",true,"2026-10-19",0L,0)));var r=service.prepare(1L,request());assertThat(r.entry().document().basic()).doesNotContainKeys("brand","displayCategoryCode");assertThat(r.entry().document().options().getFirst().registration()).doesNotContainKey("maximumBuyCount");assertThat(r.notes()).anyMatch(n->n.message().contains("블랙 2개")&&n.message().contains("브라운 3개"));}
    @Test void missingListingAndNoOwnedStockProposesZeroButUnknownNeverDoes(){for(String status:List.of("SELECTED_MISSING","UNKNOWN")){when(sources.byCodes(1L,Set.of("SKU123"))).thenReturn(Map.of("SKU123",new CoupangRecommendationSources.Source("7",null,false,null,List.of(),0,status,false,"2026-10-20",0L,0)));var reg=service.prepare(1L,request()).entry().document().options().getFirst().registration();if(status.equals("SELECTED_MISSING"))assertThat(reg).containsEntry("maximumBuyCount","0");else assertThat(reg).doesNotContainKey("maximumBuyCount");}}
    @Test void priorPriceUsesSharedCalculatorAndConfiguredDefaultsRemainExact(){
        when(defaults.get(1L)).thenReturn(new CoupangRegistrationDefaults.Settings(account,3,Map.of("outboundShippingPlaceCode","77","deliveryCompanyCode","HANJIN"),Map.of("outboundShippingTimeDay","2"),Map.of("bundleInfo.bundleType","SINGLE")));
        var settings=new CoupangPricing.Settings(4,4000,new java.math.BigDecimal("10.5"),new java.math.BigDecimal("7"),1L,"2026-10-20");when(pricing.get(1L)).thenReturn(settings);
        when(pricing.calculate(settings,30000L,null)).thenAnswer(c->new cc.ataglace.molebutter.coupang.internal.DefaultCoupangPricing(null,null).calculate(settings,30000L,null));
        when(sources.byCodes(1L,Set.of("SKU123"))).thenReturn(Map.of("SKU123",new CoupangRecommendationSources.Source("7",30000L,true,"2026-10-19",List.of(new CoupangRecommendationSources.Stock("a","블랙",2,"AVAILABLE")),1,"AVAILABLE",true,"2026-10-19",0L,0)));
        var r=service.prepare(1L,request());assertThat(r.entry().document().options().getFirst().registration()).containsEntry("salePrice","42000").containsEntry("maximumBuyCount","2").containsEntry("outboundShippingTimeDay","2");assertThat(r.entry().document().delivery()).containsEntry("deliveryCompanyCode","HANJIN");assertThat(r.notes()).anyMatch(n->n.source().contains("이전 정상 확인값"));
    }
    @Test void certificateDefaultRequiresAnExactCategoryTypeAndNeverSkipsOtherRequirements(){
        when(defaults.get(1L)).thenReturn(new CoupangRegistrationDefaults.Settings(account,1,Map.of(),Map.of(),Map.of(),List.of(new CoupangCatalog.Certification("NOT_REQUIRED","",List.of()))));
        when(editor.category(1L,"123")).thenReturn(new CoupangEditor.CategoryRules("123",true,List.of(),List.of(),List.of(new CoupangEditor.CertificateRule("NOT_REQUIRED","대상 아님","OPTIONAL","NONE"),new CoupangEditor.CertificateRule("KC","KC 인증","MANDATORY","CODE")),List.of(),List.of("NEW")));
        var r=service.prepare(1L,request());assertThat(r.entry().document().options().getFirst().certifications()).isEmpty();assertThat(r.notes()).anyMatch(n->n.field().equals("인증·서류"));
        when(editor.category(1L,"123")).thenReturn(new CoupangEditor.CategoryRules("123",true,List.of(),List.of(),List.of(),List.of(),List.of("NEW")));
        assertThat(service.prepare(1L,request()).entry().document().options().getFirst().certifications()).isEmpty();
    }
    @Test void tokenIsActorAccountAndTenMinuteBoundAndDatesNeverRegenerate(){var r=service.prepare(1L,request());assertThatThrownBy(()->service.get(2L,r.token())).isInstanceOf(InputValidationFailure.class);account="b".repeat(64);assertThatThrownBy(()->service.get(1L,r.token())).isInstanceOf(InputValidationFailure.class);account="a".repeat(64);clock.now=clock.now.plusSeconds(599);assertThat(service.get(1L,r.token()).entry().document().settings()).containsEntry("saleStartedAt","2026-10-20T13:00:00");clock.now=clock.now.plusSeconds(1);assertThatThrownBy(()->service.get(1L,r.token())).isInstanceOf(InputValidationFailure.class);}
    @Test void brandLogoUsesDisplayTitleAndSitsBetweenPrefixesAndProductPhotos(){
        String guide="https://assets.test/common/guide.png",logo="https://assets.test/common/daks.png";
        when(defaults.get(1L)).thenReturn(new CoupangRegistrationDefaults.Settings(account,2,Map.of(),Map.of(),Map.of(),List.of(),List.of(guide),List.of(new CoupangRegistrationDefaults.BrandLogo("닥스",List.of("DAKS"),logo))));
        for(String url:List.of(guide,logo))when(r2.resolve(1L,url)).thenReturn(new R2Images.Image("common/"+url.substring(url.lastIndexOf('/')+1),url,"image/png",300,150,500));files("official/01.png");
        var r=service.prepare(1L,request());var option=r.entry().document().options().getFirst();assertThat(option.contents()).extracting(CoupangWorkingCopies.Content::content).containsExactly(guide,logo,file("official/01.png").url());assertThat(option.contents().get(1).purpose()).isEqualTo("BRAND_LOGO");assertThat(option.images()).extracting(CoupangWorkingCopies.Image::url).containsExactly(file("official/01.png").url());
        assertThat(r.notes()).anyMatch(n->n.field().equals("브랜드 로고")&&n.source().contains("노출상품명"));verify(defaults,never()).save(any(),any());
        when(r2.resolve(1L,logo)).thenThrow(new InputValidationFailure("삭제된 이미지"));assertThat(service.prepare(1L,request()).entry().document().options().getFirst().contents()).anyMatch(c->logo.equals(c.content())&&"BRAND_LOGO".equals(c.purpose()));
    }
    @Test void prefixesPrecedePhotosAndMissingConfiguredImagesRemainForBlockingSubmissionInspection(){
        var first="https://assets.test/common/first.png";var second="https://assets.test/common/second.png";
        when(defaults.get(1L)).thenReturn(new CoupangRegistrationDefaults.Settings(account,2,Map.of(),Map.of(),Map.of(),List.of(),List.of(first,second)));
        when(r2.resolve(1L,first)).thenThrow(new InputValidationFailure("삭제된 이미지"));
        when(r2.resolve(1L,second)).thenReturn(new R2Images.Image("common/second.png",second,"image/png",300,900,500));
        files("official/01.png","processed/사이즈.png");var r=service.prepare(1L,request());var option=r.entry().document().options().getFirst();
        assertThat(option.contents()).extracting(CoupangWorkingCopies.Content::content).containsExactly(first,second,file("official/01.png").url(),file("processed/사이즈.png").url());
        assertThat(option.images()).hasSize(1);assertThat(option.images().getFirst().url()).contains("official/01.png");
        assertThat(r.notes()).anyMatch(n->n.field().equals("공통 상세설명 이미지")&&n.source().equals("조회 실패"));
        assertThat(service.get(1L,r.token()).entry().document()).isEqualTo(r.entry().document());verify(uploads,never()).start(any(),any());
    }
    @Test void initialSearchTagsUseExplicitSourceColorsAndNeverTheSkuOrInferredDisplayGender(){
        when(workspace.observed(1L,"SOURCE1","DAKS")).thenReturn(new ProductLookupDto("SOURCE1","DAKS","닥스","닥스 남성 블랙 지갑",200000,150000,List.of(),List.of(),Map.of("소재","천연가죽(소)","색상","화이트"),"FREE",null,Map.of(),false,null,null,null,"블랙 지갑 SOURCE1"));
        var r=service.prepare(1L,request());var words=r.entry().document().options().getFirst().registration().get("searchTags").split(", ");
        assertThat(words).contains("닥스","화이트","지갑","카드","소가죽").doesNotContain("SKU123","SOURCE1","남성","아버지","블랙");
        assertThat(r.searchTagContext().colors()).containsExactly("화이트");assertThat(r.notes()).anyMatch(n->n.field().equals("검색어"));
    }
    @Test void colorTonePrefillUsesOnlySupportedSearchFieldAndExplicitSourceTitle(){
        when(editor.category(1L,"123")).thenReturn(new CoupangEditor.CategoryRules("123",true,List.of(new CoupangEditor.AttributeRule("패션 의류/잡화 색상계열","NONE","OPTIONAL",null,"STRING",null,List.of())),List.of(),List.of(),List.of(),List.of("NEW")));
        for(String color:List.of("진한 브라운","블랙 화이트","베이지")){
            when(workspace.observed(1L,"SOURCE1","DAKS")).thenReturn(new ProductLookupDto("SOURCE1","DAKS","닥스","닥스 "+color+" 지갑",200000,150000,List.of(),List.of(),Map.of(),"FREE",null,Map.of(),false,null,null,null,"닥스 "+color+" 지갑 SOURCE1"));
            var r=service.prepare(1L,request());var attrs=r.entry().document().options().getFirst().attributes();
            if(color.equals("진한 브라운"))assertThat(attrs).containsExactly(new CoupangCatalog.Attribute("패션 의류/잡화 색상계열","브라운 톤","NONE"));else assertThat(attrs).isEmpty();
        }
    }
    @Test void freeShippingFromLegacyDefaultsFillsZeroFeesAndPreservesReturnFees(){
        for(var fees:List.of(Map.<String,String>of(),Map.of("deliveryCharge","3000","freeShipOverAmount","50000"))){
            var delivery=new HashMap<>(fees);delivery.putAll(Map.of("deliveryChargeType","FREE","deliveryChargeOnReturn","4000","returnCharge","4000"));
            when(defaults.get(1L)).thenReturn(new CoupangRegistrationDefaults.Settings(account,3,delivery,Map.of(),Map.of()));
            var r=service.prepare(1L,request());assertThat(r.entry().document().delivery()).containsEntry("deliveryCharge","0").containsEntry("freeShipOverAmount","0").containsEntry("deliveryChargeOnReturn","4000").containsEntry("returnCharge","4000");
            assertThat(r.notes()).noneMatch(n->n.source().equals("누락")&&n.field().equals("deliveryCharge"));
        }
        when(defaults.get(1L)).thenReturn(new CoupangRegistrationDefaults.Settings(account,3,Map.of("deliveryChargeType","NOT_FREE","deliveryCharge","3000"),Map.of(),Map.of()));
        assertThat(service.prepare(1L,request()).entry().document().delivery()).containsEntry("deliveryCharge","3000");
    }
    @Test void expiredObservationStopsBeforeCatalogAndPreservesTool(){when(workspace.observed(1L,"SOURCE1","DAKS")).thenThrow(new InputValidationFailure("조회 만료"));assertThatThrownBy(()->service.prepare(1L,request())).hasMessageContaining("조회 만료");verifyNoInteractions(editor,brands,r2,copies);}
    static class MutableClock extends Clock{Instant now=Instant.parse("2026-10-20T04:00:00Z");public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return now;}}
}
