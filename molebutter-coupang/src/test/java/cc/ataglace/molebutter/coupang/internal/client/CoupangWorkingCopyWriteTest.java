package cc.ataglace.molebutter.coupang.internal.client;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.*;
import tools.jackson.databind.node.*;
import cc.ataglace.molebutter.common.api.*;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
import cc.ataglace.molebutter.media.api.*;
import cc.ataglace.molebutter.coupang.api.*;
import cc.ataglace.molebutter.coupang.api.CoupangWriteGateway.*;
import cc.ataglace.molebutter.coupang.api.CoupangWorkingCopies.Document;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class CoupangWorkingCopyWriteTest {
    final ObjectMapper json = new ObjectMapper();
    final CoupangProductClient client = mock(CoupangProductClient.class);
    final ImageAssets assets = mock(ImageAssets.class);
    final R2Images r2 = mock(R2Images.class);
    final BusinessAccess access = mock(BusinessAccess.class);
    final DefaultCoupangWriteGateway writer = new DefaultCoupangWriteGateway(access, client, assets, json,
            mock(CoupangBrands.class), r2);
    final String option = UUID.randomUUID().toString();
    final Mapping mapping = new Mapping("a".repeat(64), "123", List.of(new OptionMapping(option, "111", "222")));
    ObjectNode raw;
    ObjectNode inventory;
    Document observed;
    @BeforeEach
    void setup() {
        raw = json
                .readTree(
                        """
                                {"sellerProductId":123,"vendorId":"A-test","sellerProductName":"original","brand":"brand","displayCategoryCode":10,"statusName":"승인완료","deliveryCharge":0,"vendorUserId":"server","future":{"keep":true},"items":[{"sellerProductItemId":111,"vendorItemId":222,"itemName":"블랙","externalVendorSku":"sku","salePrice":120000,"maximumBuyCount":9,"originalPrice":160000,"futureItem":{"keep":true},"attributes":[{"attributeTypeName":"색상","attributeValueName":"블랙","exposed":"EXPOSED","future":true}],"images":[{"imageType":"REPRESENTATION","imageOrder":0,"vendorPath":"https://image.example.test/a.jpg"}],"contents":[{"contentsType":"HTML","contentDetails":[{"detailType":"TEXT","content":"original"}]}]}]}
                                """)
                .asObject();
        inventory = json.readTree("{\"salePrice\":120000,\"amountInStock\":9}").asObject();
        var parser = new CoupangProductClient("A-test", "access", "secret", json, new CoupangHttpTransport(),
                Clock.systemUTC());
        when(client.editorDocument(any(), anyMap(), anyMap()))
                .thenAnswer(i -> parser.editorDocument(i.getArgument(0), i.getArgument(1), i.getArgument(2)));
        when(client.accountKey()).thenReturn(mapping.accountKey());
        when(client.vendorId()).thenReturn("A-test");
        when(client.registrationUserId()).thenReturn("server");
        when(client.now()).thenReturn(Instant.parse("2026-10-15T00:00:00Z"));
        when(client.operation(anyLong(),isNull(),any())).thenAnswer(i -> ((Supplier<?>)i.getArgument(2)).get());
        when(client.writeSession(any())).thenAnswer(i -> ((Supplier<?>) i.getArgument(0)).get());
        when(client.withFirstResponseGap(any(), any())).thenAnswer(i -> ((Supplier<?>) i.getArgument(1)).get());
        when(client.rawProduct("123")).thenAnswer(i -> raw.deepCopy());
        when(client.rawInventory(anyString(), anyBoolean(), anyBoolean())).thenAnswer(i -> inventory.deepCopy());
        when(client.rawInventory(anyString())).thenAnswer(i -> inventory.deepCopy());
        when(client.rawSummary(anyString())).thenReturn(json.createArrayNode());
        when(client.rawCategory(anyString())).thenReturn(new CoupangEditor.CategoryRules("10", true, List
                .of(new CoupangEditor.AttributeRule("색상", "EXPOSED", "OPTIONAL", "NONE", "STRING", null, List.of())),
                List.of(), List.of(), List.of(), List.of()));
        when(client.creationCategory(anyString())).thenReturn(new CoupangEditor.CategoryRules("10", true,
                List.of(new CoupangEditor.AttributeRule("색상", "EXPOSED", "OPTIONAL", "NONE", "STRING", null, List.of())),
                List.of(),List.of(),List.of(),List.of(),"2026-10-15T00:00:00Z"));
        when(r2.resolve(anyLong(), anyString()))
                .thenAnswer(i -> new R2Images.Image("fixture", i.getArgument(1), "image/png", 500, 500, 100));
        when(client.write(anyString(), anyString(), anyString(), nullable(String.class)))
                .thenReturn(new CoupangHttpTransport.Response(200, "{\"code\":\"SUCCESS\"}".getBytes()));
        when(client.write(anyString(), anyString(), anyString(), nullable(String.class), any(Runnable.class)))
                .thenAnswer(i -> client.write(i.getArgument(0), i.getArgument(1), i.getArgument(2), i.getArgument(3)));
        observed = CoupangInputs.from(client.editorDocument(raw,
                Map.of("222", new CoupangCatalog.CurrentInventory("222", 120000L, 9L, true)), Map.of()), mapping);
    }
    CoupangEditing.Change change(String path, Object value) {
        return new CoupangEditing.Change(path, path.startsWith("options.") ? option : null, value);
    }
    Prepared prepare(CoupangEditing.Change... edits) {
        var list = List.of(edits);
        var wanted = CoupangInputs.apply(json, observed, list);
        return writer.prepare(1L, wanted, mapping, observed, list);
    }
    @Test void localBrandLogoPurposeNeverEntersCoupangContentPayload(){
        var logo=new CoupangWorkingCopies.Content(java.util.UUID.randomUUID().toString(),"IMAGE","IMAGE","https://assets.example.test/common/daks.png",null,"BRAND_LOGO");
        var prepared=prepare(change("options.contents",List.of(logo)));
        assertThat(prepared.steps()).isNotEmpty();String sent=prepared.steps().getFirst().bodyJson();assertThat(sent).contains("daks.png").doesNotContain("BRAND_LOGO","purpose");
    }
    @Test void generatedSearchTagsBecomeAStringArrayOnTheMockWireAndLeaveUnknownFieldsIntact(){
        var generated=new cc.ataglace.molebutter.coupang.internal.DefaultCoupangSearchTags().generate(new CoupangSearchTags.Input("닥스 남성 반지갑","닥스",new CoupangSearchTags.Context("남성","소가죽","반지갑",List.of("블랙"))));
        var before=raw.deepCopy();var prepared=prepare(change("options.registration.searchTags",String.join(", ",generated.tags())));
        var step=prepared.steps().stream().filter(s->s.type()==Type.PRODUCT).findFirst().orElseThrow();
        var body=json.readTree(step.bodyJson());assertThat(body.path("items").get(0).path("searchTags").isArray()).isTrue();
        assertThat(body.path("items").get(0).path("searchTags").valueStream().map(v->v.asString()).toList()).containsExactlyElementsOf(generated.tags());
        assertThat(body.path("future")).isEqualTo(raw.path("future"));assertThat(raw).isEqualTo(before);
        var result=writer.execute(1L,prepared,step,mapping,s->{});assertThat(json.readTree(json.readTree(result.requestJson()).path("bodyJson").asString()).path("items").get(0).path("searchTags")).isEqualTo(body.path("items").get(0).path("searchTags"));
    }
    @Test void commaPricesReachTheMockWireAsIntegersAndKeepTheUneditedBaseline(){
        var baseline=raw.deepCopy();var prepared=prepare(change("options.registration.salePrice"," 139,000 "));
        assertThat(prepared.editIntent().changes().getFirst().value()).isEqualTo("139000");
        var result=writer.execute(1L,prepared,prepared.steps().getFirst(),mapping,s->{});
        assertThat(prepared.steps().getFirst().path()).endsWith("/prices/139000");
        assertThat(result.requestJson()).contains("139000").doesNotContain("139,000");
        assertThat(json.readTree(prepared.steps().getFirst().expectedJson()).path("salePrice").isIntegralNumber()).isTrue();
        assertThat(raw).isEqualTo(baseline);
        var equal=prepare(change("options.registration.salePrice","120,000"));assertThat(equal.steps()).isEmpty();
    }
    @Test void actualDispatchPreservesRedactedReasonAlongsideTheExactRequest() {
        var prepared = prepare(change("options.registration.salePrice", "130000"));
        when(client.write(anyString(), anyString(), anyString(), nullable(String.class))).thenReturn(
                new CoupangHttpTransport.Response(200, "{\"code\":\"ERROR\",\"message\":\"필수 입력값을 확인하세요\",\"details\":\"연락처 010-1234-5678\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var result = writer.execute(1L, prepared, prepared.steps().getFirst(), mapping, s -> {});
        assertThat(result.state()).isEqualTo(State.FAILED);
        assertThat(result.rejectionDetails()).contains("필수 입력값").doesNotContain("010", "1234");
        assertThat(result.requestJson()).contains("130000");
        verify(client, times(1)).write(anyString(), anyString(), anyString(), nullable(String.class));
    }
    @Test void transientPriceAndStockFailuresRetryThroughTheRealClientAndRecheckTheGuard() {
        for (var kind : List.of(Type.PRICE, Type.STOCK)) {
            inventory.put("salePrice",120000).put("amountInStock",9);
            var p=prepare(change(kind==Type.PRICE?"options.registration.salePrice":"options.registration.maximumBuyCount",
                    kind==Type.PRICE?"130000":"7"));
            var transport=mock(CoupangHttpTransport.class);var calls=new java.util.concurrent.atomic.AtomicInteger();
            when(transport.send(anyString(),anyString(),anyString(),anyString(),nullable(byte[].class),any())).thenAnswer(i->{
                boolean first=calls.incrementAndGet()==1;
                if(!first)inventory.put(kind==Type.PRICE?"salePrice":"amountInStock",kind==Type.PRICE?130000:7);
                return new CoupangHttpTransport.Response(200,(first?CoupangRetryTest.PROCESSING:"{\"code\":\"SUCCESS\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            });
            var actualClient=new CoupangProductClient("A-test","a","s",json,transport,Clock.systemUTC());
            doAnswer(i->actualClient.writeSession(()->actualClient.write(i.getArgument(0),i.getArgument(1),i.getArgument(2),i.getArgument(3),i.getArgument(4))))
                    .when(client).write(anyString(),anyString(),anyString(),nullable(String.class),any(Runnable.class));
            var checked=new java.util.concurrent.atomic.AtomicInteger();
            var result=writer.execute(1L,p,p.steps().getFirst(),mapping,s->checked.incrementAndGet());
            assertThat(result.state()).isEqualTo(State.CONFIRMED);assertThat(calls).hasValue(2);assertThat(checked).hasValue(2);
        }
    }
    @Test void externalPriceChangeBetweenRejectedWriteAndRetryPreventsAnotherDispatch() {
        var p=prepare(change("options.registration.salePrice","130000"));
        var transport=mock(CoupangHttpTransport.class);var calls=new java.util.concurrent.atomic.AtomicInteger();
        when(transport.send(anyString(),anyString(),anyString(),anyString(),nullable(byte[].class),any())).thenAnswer(i->{
            calls.incrementAndGet();inventory.put("salePrice",125000);
            return new CoupangHttpTransport.Response(200,CoupangRetryTest.PROCESSING.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        });
        var actualClient=new CoupangProductClient("A-test","a","s",json,transport,Clock.systemUTC());
        doAnswer(i->actualClient.writeSession(()->actualClient.write(i.getArgument(0),i.getArgument(1),i.getArgument(2),i.getArgument(3),i.getArgument(4))))
                .when(client).write(anyString(),anyString(),anyString(),nullable(String.class),any(Runnable.class));
        var result=writer.execute(1L,p,p.steps().getFirst(),mapping,s->{});
        assertThat(result.state()).isEqualTo(State.FAILED);assertThat(result.code()).isEqualTo("MAPPING_CHANGED");assertThat(calls).hasValue(1);
    }
    @Test void approvalRetryKeepsTheCreatedIdentifierAndDoesNotCreateAnotherProduct() {
        var p=writer.prepare(1L,creation(),null,creation(),List.of());
        var created=json.readTree(p.steps().getFirst().bodyJson()).deepCopy().asObject().put("sellerProductId",555).put("statusName","승인완료");
        created.path("items").get(0).asObject().put("sellerProductItemId",111).put("vendorItemId",222);
        when(client.rawProduct("555")).thenReturn(created);
        var assigned=new Mapping(mapping.accountKey(),"555",List.of(new OptionMapping(p.mapping().options().getFirst().optionId(),"111","222")));
        var transport=mock(CoupangHttpTransport.class);
        when(transport.send(anyString(),anyString(),anyString(),anyString(),nullable(byte[].class),any())).thenReturn(
                new CoupangHttpTransport.Response(200,CoupangRetryTest.NOT_READY.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                new CoupangHttpTransport.Response(200,"{\"code\":\"SUCCESS\",\"data\":555}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var actualClient=new CoupangProductClient("A-test","a","s",json,transport,Clock.systemUTC());
        doAnswer(i->actualClient.writeSession(()->actualClient.write(i.getArgument(0),i.getArgument(1),i.getArgument(2),i.getArgument(3),i.getArgument(4))))
                .when(client).write(anyString(),anyString(),anyString(),nullable(String.class),any(Runnable.class));
        var checked=new java.util.concurrent.atomic.AtomicInteger();
        var result=writer.execute(1L,p,p.steps().getLast(),assigned,s->checked.incrementAndGet());
        assertThat(result.state()).isEqualTo(State.CONFIRMED);assertThat(result.mapping().sellerProductId()).isEqualTo("555");assertThat(checked).hasValue(2);
        verify(transport,times(2)).send(eq("PUT"),endsWith("/555/approvals"),eq(""),anyString(),isNull(),any());
        verify(transport,never()).send(eq("POST"),anyString(),anyString(),anyString(),any(),any());
    }
    @Test void storedOptionPreparationDoesNotReadCoupangAndDispatchRechecksBeforeAnyWrite() {
        var edit=change("options.registration.salePrice","130000");var wanted=CoupangInputs.apply(json,observed,List.of(edit));
        clearInvocations(client);var prepared=writer.prepareStoredOption(1L,wanted,mapping,observed,List.of(edit));
        assertThat(prepared.steps()).singleElement().satisfies(step->{assertThat(step.type()).isEqualTo(Type.PRICE);assertThat(step.baselineJson()).contains("120000");});
        verify(client,never()).rawProduct(anyString());verify(client,never()).rawInventory(anyString(),anyBoolean(),anyBoolean());verify(client,never()).write(anyString(),anyString(),anyString(),nullable(String.class));
        prepared=new Prepared(prepared.accountKey(),prepared.mapping(),prepared.steps(),prepared.changes(),prepared.preparedAt(),prepared.expectedSkus(),new EditIntent(observed,List.of(edit),new CoupangOptionEdits.Basis("42","fp",1L)),4);
        inventory.put("salePrice",125000);var denied=writer.execute(1L,prepared,prepared.steps().getFirst(),mapping,step->{});
        assertThat(denied.state()).isEqualTo(State.FAILED);verify(client,never()).write(anyString(),anyString(),anyString(),nullable(String.class));
        inventory.put("salePrice",120000);raw.path("items").get(0).asObject().put("externalVendorSku","CHANGED");
        var wrongSku=writer.execute(1L,prepared,prepared.steps().getFirst(),mapping,step->{});assertThat(wrongSku.state()).isEqualTo(State.FAILED);verify(client,never()).write(anyString(),anyString(),anyString(),nullable(String.class));
        raw.path("items").get(0).asObject().put("externalVendorSku","sku");clearInvocations(client);var accepted=writer.execute(1L,prepared,prepared.steps().getFirst(),mapping,step->{});
        verify(client,times(1)).rawProduct("123");verify(client,times(2)).rawInventory("222",true,false);verify(client,times(1)).write(eq("PUT"),contains("/prices/130000"),anyString(),nullable(String.class));
        assertThat(accepted.state()).isIn(State.CONFIRMED,State.ACCEPTED);
        assertThatThrownBy(()->writer.prepareStoredOption(1L,wanted,mapping,observed,List.of(edit,edit))).isInstanceOf(InputValidationFailure.class);
        var other=change("basic.sellerProductName","other");assertThatThrownBy(()->writer.prepareStoredOption(1L,CoupangInputs.apply(json,observed,List.of(other)),mapping,observed,List.of(other))).isInstanceOf(InputValidationFailure.class);
    }
    @Test void storedStockZeroAndAlreadyAppliedNeverResend() {
        var edit=change("options.registration.maximumBuyCount","0");var wanted=CoupangInputs.apply(json,observed,List.of(edit));
        var prepared=writer.prepareStoredOption(1L,wanted,mapping,observed,List.of(edit));
        prepared=new Prepared(prepared.accountKey(),prepared.mapping(),prepared.steps(),prepared.changes(),prepared.preparedAt(),prepared.expectedSkus(),new EditIntent(observed,List.of(edit),new CoupangOptionEdits.Basis("42","fp",null)),4);
        inventory.put("amountInStock",0);clearInvocations(client);var result=writer.execute(1L,prepared,prepared.steps().getFirst(),mapping,step->{});
        assertThat(result.state()).isEqualTo(State.CONFIRMED);verify(client,never()).write(anyString(),anyString(),anyString(),nullable(String.class));
    }
    @Test void salesAndDeletionUseExplicitLifecycleStepsAndDoNotSendUnrelatedInputs(){
        inventory.put("onSale",true);
        var prepared=writer.prepareAction(1L,mapping,observed,"DELETE",null);
        assertThat(prepared.steps()).extracting(Step::type).containsExactly(Type.SALE_STOP,Type.DELETE);
        assertThat(prepared.editIntent().changes()).isEmpty();
        var delete=prepared.steps().getLast();
        var blocked=writer.execute(1L,prepared,delete,mapping,step->{});
        assertThat(blocked.state()).isEqualTo(State.FAILED);
        verify(client,never()).write(eq("DELETE"),anyString(),anyString(),nullable(String.class));
        when(client.write(eq("PUT"),anyString(),anyString(),nullable(String.class))).thenAnswer(i->{inventory.put("onSale",false);return new CoupangHttpTransport.Response(200,"{\"code\":\"SUCCESS\"}".getBytes());});
        assertThat(writer.execute(1L,prepared,prepared.steps().getFirst(),mapping,step->{}).state()).isEqualTo(State.CONFIRMED);
        when(client.write(eq("DELETE"),anyString(),anyString(),nullable(String.class))).thenReturn(new CoupangHttpTransport.Response(200,"{\"code\":\"SUCCESS\",\"data\":\"123\"}".getBytes()));
        assertThat(writer.execute(1L,prepared,delete,mapping,step->{}).state()).isEqualTo(State.CONFIRMED);
        verify(client).deletedReceipt(eq("123"),any());
    }
    @Test void ambiguousDeletionNeverRetriesOrTreatsGenericAbsenceAsDeletion(){
        inventory.put("onSale",false);
        var p=writer.prepareAction(1L,mapping,observed,"DELETE",null);var step=p.steps().getFirst();
        when(client.write(eq("DELETE"),anyString(),anyString(),nullable(String.class))).thenReturn(new CoupangHttpTransport.Response(500,"{}".getBytes()));
        var result=writer.execute(1L,p,step,mapping,s->{});assertThat(result.state()).isEqualTo(State.UNKNOWN);
        doThrow(new CoupangFailure(CoupangFailure.Kind.PRODUCT_ABSENT)).when(client).rawProduct("123");
        assertThat(writer.reconcile(1L,p,step,result).state()).isEqualTo(State.UNKNOWN);
        verify(client,times(1)).write(eq("DELETE"),anyString(),anyString(),nullable(String.class));
        doThrow(new CoupangListingMissing("COUPANG_DELETED")).when(client).rawProduct("123");
        assertThat(writer.reconcile(1L,p,step,result).state()).isEqualTo(State.CONFIRMED);
    }
    @Test void lifecyclePreflightRejectsChangedOptionIdentity(){
        inventory.put("onSale",true);var p=writer.prepareAction(1L,mapping,observed,"SALE_STOP","222");
        raw.path("items").get(0).asObject().put("vendorItemId",333);
        assertThat(writer.execute(1L,p,p.steps().getFirst(),mapping,s->{}).state()).isEqualTo(State.FAILED);
        verify(client,never()).write(anyString(),anyString(),anyString(),nullable(String.class));
    }
    @Test
    void attributesWithTheSameNameUseExposureAsPartOfTheirIdentity() {
        var rows = raw.path("items").get(0).asObject().putArray("attributes");
        rows.addObject().put("attributeTypeName", "색상").put("attributeValueName", "검색값").put("exposed", "NONE")
                .put("editable", false).put("future", "search");
        rows.addObject().put("attributeTypeName", "색상").put("attributeValueName", "블랙").put("exposed", "EXPOSED")
                .put("editable", true).put("future", "purchase");
        var editor = client.editorDocument(raw,
                Map.of("222", new CoupangCatalog.CurrentInventory("222", 120000L, 9L, true)), Map.of());
        assertThat(editor.options().getFirst().attributePermissions()).containsExactly(
                new CoupangEditor.AttributePermission("색상", "NONE", false),
                new CoupangEditor.AttributePermission("색상", "EXPOSED", true));
        observed = CoupangInputs.from(editor, mapping);
        var edit = new CoupangEditing.Change("options.attributes.value", option, "브라운", List.of("색상", "EXPOSED"),
                "SET");
        var p = prepare(edit);
        rows.get(0).asObject().put("attributeValueName", "최신 검색값");
        var sent = new ArrayList<Step>();
        assertThat(writer.execute(1L, p, p.steps().getFirst(), mapping, sent::add).state()).isEqualTo(State.CONFIRMED);
        var wire = json.readTree(sent.getFirst().bodyJson()).path("items").get(0).path("attributes");
        assertThat(wire.get(1).path("attributeValueName").asString()).isEqualTo("브라운");
        assertThat(wire.get(0)).isEqualTo(rows.get(0));
        rows.get(1).asObject().put("editable", false);
        rows.get(0).asObject().put("editable", true);
        clearInvocations(client);
        assertThat(writer.execute(1L, p, p.steps().getFirst(), mapping, ignored -> {
        }).state()).isEqualTo(State.FAILED);
        verify(client, never()).write(any(), any(), any(), any());
    }
    @Test
    void explicit130000WinsOverFresh150000AndOnlyPriceEndpointIsUsed() {
        var p = prepare(change("options.registration.salePrice", "130000"));
        inventory.put("salePrice", 150000);
        raw.put("sellerProductName", "remote name");
        var sent = new ArrayList<Step>();
        writer.execute(1L, p, p.steps().getFirst(), mapping, sent::add);
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst().path()).endsWith("/prices/130000");
        assertThat(sent.getFirst().query()).isEqualTo("forceSalePriceUpdate=false");
        assertThat(json.readTree(sent.getFirst().baselineJson()).path("salePrice").asInt()).isEqualTo(150000);
        verify(client).write(eq("PUT"), endsWith("/prices/130000"), anyString(), isNull());
    }
    @Test
    void untouchedLatestRawValuesAndUnknownFieldsSurviveInformationUpdate() {
        var p = prepare(change("basic.sellerProductName", "wanted"));
        raw.put("deliveryCharge", 9000);
        raw.put("generalProductName", "remote product");
        raw.path("items").get(0).asObject().put("modelNo", "remote model");
        var sent = new ArrayList<Step>();
        writer.execute(1L, p, p.steps().getFirst(), mapping, sent::add);
        var wire = json.readTree(sent.getFirst().bodyJson());
        assertThat(wire.path("sellerProductName").asString()).isEqualTo("wanted");
        assertThat(wire.path("deliveryCharge").asInt()).isEqualTo(9000);
        assertThat(wire.path("generalProductName").asString()).isEqualTo("remote product");
        assertThat(wire.path("items").get(0).path("modelNo").asString()).isEqualTo("remote model");
        assertThat(wire.path("future").path("keep").asBoolean()).isTrue();
        assertThat(wire.path("items").get(0).path("futureItem").path("keep").asBoolean()).isTrue();
    }
    @Test
    void allDirectScalarInputsWinOverRemoteValueChanges() {
        var p = prepare(change("options.registration.modelNo", "mine"));
        raw.path("items").get(0).asObject().put("modelNo", "external");
        var sent = new ArrayList<Step>();
        writer.execute(1L, p, p.steps().getFirst(), mapping, sent::add);
        assertThat(json.readTree(sent.getFirst().bodyJson()).path("items").get(0).path("modelNo").asString())
                .isEqualTo("mine");
    }
    @ParameterizedTest
    @ValueSource(strings = {"salePrice", "maximumBuyCount", "originalPrice"})
    void dedicatedOptionWrites(String field) {
        var p = prepare(change("options.registration." + field, field.equals("maximumBuyCount") ? "0" : "130000"));
        assertThat(p.steps()).hasSize(1);
        assertThat(p.steps().getFirst().type()).isEqualTo(field.equals("salePrice")
                ? Type.PRICE
                : field.equals("maximumBuyCount") ? Type.STOCK : Type.ORIGINAL_PRICE);
    }
    @Test
    void optionIdentifierChangeStopsBeforeWrite() {
        var p = prepare(change("basic.sellerProductName", "mine"));
        raw.path("items").get(0).asObject().put("vendorItemId", 999);
        assertThat(writer.execute(1L, p, p.steps().getFirst(), mapping, ignored -> {
        }).state()).isEqualTo(State.FAILED);
        verify(client, never()).write(any(), any(), any(), any());
    }
    @Test
    void optionStructureChangeStopsBeforeWrite() {
        var p = prepare(change("basic.sellerProductName", "mine"));
        raw.path("items").asArray().add(raw.path("items").get(0).deepCopy());
        assertThat(writer.execute(1L, p, p.steps().getFirst(), mapping, ignored -> {
        }).state()).isEqualTo(State.FAILED);
        verify(client, never()).write(any(), any(), any(), any());
    }
    @Test
    void accountChangeStopsBeforeReadOrWrite() {
        var p = prepare(change("basic.sellerProductName", "mine"));
        when(client.accountKey()).thenReturn("b".repeat(64));
        assertThat(writer.execute(1L, p, p.steps().getFirst(), mapping, ignored -> {
        }).code()).isEqualTo("ACCOUNT_CHANGED");
        verify(client, never()).write(any(), any(), any(), any());
    }
    @Test
    void unavailableFreshReadNeverDispatches() {
        var p = prepare(change("basic.sellerProductName", "mine"));
        when(client.rawProduct("123")).thenThrow(new CoupangFailure(CoupangFailure.Kind.NETWORK));
        var sent = new ArrayList<Step>();
        assertThat(writer.execute(1L, p, p.steps().getFirst(), mapping, sent::add).state()).isEqualTo(State.FAILED);
        assertThat(sent).isEmpty();
        verify(client, never()).write(any(), any(), any(), any());
    }
    @Test
    void timeoutAfterDispatchIsUnknownAndReconciliationDoesNotWrite() {
        var p = prepare(change("options.registration.salePrice", "130000"));
        when(client.write(any(), any(), any(), any())).thenThrow(new CoupangFailure(CoupangFailure.Kind.NETWORK));
        var result = writer.execute(1L, p, p.steps().getFirst(), mapping, ignored -> {
        });
        assertThat(result.state()).isEqualTo(State.UNKNOWN);
        inventory.put("salePrice", 130000);
        assertThat(writer.reconcile(1L, p, p.steps().getFirst(), result).state()).isEqualTo(State.CONFIRMED);
        verify(client, times(1)).write(any(), any(), any(), any());
    }
    @Test
    void alreadyAppliedPriceDoesNotDispatchAgain() {
        var p = prepare(change("options.registration.salePrice", "130000"));
        inventory.put("salePrice", 130000);
        assertThat(writer.execute(1L, p, p.steps().getFirst(), mapping, ignored -> {
        }).state()).isEqualTo(State.CONFIRMED);
        verify(client, never()).write(any(), any(), any(), any());
    }
    @ParameterizedTest
    @ValueSource(strings = {"basic.sellerProductId", "source", "settings.vendorUserId", "options.id",
            "options.registration.vendorItemId", "options.registration.autoPricingInfoView.active"})
    void forbiddenFieldsCannotBeDeclared(String path) {
        assertThatThrownBy(() -> prepare(change(path, "forged"))).isInstanceOf(InputValidationFailure.class);
    }
    @Test
    void localDocumentMustMatchDeclaredChanges() {
        var c = change("basic.sellerProductName", "mine");
        var wanted = CoupangInputs.apply(json, observed, List.of(c));
        var injected = json.valueToTree(wanted).asObject();
        injected.path("basic").asObject().put("generalProductName", "hidden");
        assertThatThrownBy(() -> CoupangInputs.verify(json, observed, json.treeToValue(injected, Document.class),
                List.of(c), false)).isInstanceOf(InputValidationFailure.class);
    }
    @Test
    void dottedSettingsAreLiteralInputKeys() {
        var next = CoupangInputs.apply(json, observed, List.of(change("settings.bundleInfo.bundleType", "SINGLE")));
        assertThat(next.settings()).containsEntry("bundleInfo.bundleType", "SINGLE");
    }
    @Test
    void historicalWritesRequireANewPreview() {
        var p = prepare(change("basic.sellerProductName", "mine"));
        var old = new Prepared(p.accountKey(), p.mapping(), p.steps(), p.changes(), p.preparedAt(), p.expectedSkus(),
                p.editIntent(), 2);
        assertThat(writer.execute(1L, old, p.steps().getFirst(), mapping, ignored -> {
        }).code()).isEqualTo("LEGACY_INTENT");
        verify(client, never()).write(any(), any(), any(), any());
    }
    @Test
    void blockedRemoteStateStopsBeforeWrite() {
        var p = prepare(change("basic.sellerProductName", "mine"));
        raw.put("statusName", "승인대기중");
        assertThat(writer.execute(1L, p, p.steps().getFirst(), mapping, ignored -> {
        }).state()).isEqualTo(State.FAILED);
        verify(client, never()).write(any(), any(), any(), any());
    }
    Document creation() {
        var tree = json.valueToTree(observed).asObject();
        tree.path("basic").asObject().put("displayProductName", "").put("generalProductName", "");
        tree.path("settings").asObject().put("brandId", "KR-1").put("saleStartedAt", "2026-10-15T00:00:00")
                .put("saleEndedAt", "2099-12-31T23:59:59");
        var delivery = tree.path("delivery").asObject();
        delivery.put("deliveryMethod", "SEQUENCIAL").put("deliveryCompanyCode", "CJGLS")
                .put("deliveryChargeType", "FREE").put("deliveryCharge", "0").put("freeShipOverAmount", "0")
                .put("deliveryChargeOnReturn", "3000").put("returnCharge", "3000").put("remoteAreaDeliverable", "N")
                .put("unionDeliveryType", "NOT_UNION_DELIVERY").put("outboundShippingPlaceCode", "123")
                .put("returnCenterCode", "456").put("returnChargeName", "반품지")
                .put("companyContactNumber", "010-1234-5678").put("returnZipCode", "12345").put("returnAddress", "서울")
                .put("returnAddressDetail", "상세");
        tree.path("options").get(0).path("registration").asObject().put("maximumBuyForPerson", "0")
                .put("maximumBuyForPersonPeriod", "1").put("outboundShippingTimeDay", "1").put("unitCount", "1")
                .put("adultOnly", "EVERYONE").put("taxType", "TAX").put("parallelImported", "NOT_PARALLEL_IMPORTED")
                .put("overseasPurchased", "NOT_OVERSEAS_PURCHASED").put("pccNeeded", "false");
        return json.treeToValue(tree, Document.class);
    }
    @Test
    void newRegistrationSupportsEmptyOptionalNamesAndOnlyUsesServerIdentity() {
        var p = writer.prepare(1L, creation(), null, creation(), List.of());
        assertThat(p.steps()).extracting(Step::type).containsExactly(Type.CREATE, Type.APPROVAL);
        var body = json.readTree(p.steps().getFirst().bodyJson());
        assertThat(body.path("vendorId").asString()).isEqualTo("A-test");
        assertThat(body.path("vendorUserId").asString()).isEqualTo("server");
        assertThat(body.has("displayProductName")).isFalse();
        assertThat(body.path("requested").asBoolean()).isFalse();
        verify(client, never()).write(any(), any(), any(), any());
    }
    @Test
    void rejectedCreateStaysCreateOnReloadAndRetryWithoutLookingUpAnAssignedProduct() {
        var document=creation();var prepared=writer.prepare(1L,document,null,document,List.of());
        when(client.write(any(),any(),any(),any())).thenReturn(new CoupangHttpTransport.Response(200,
            "{\"code\":\"ERROR\",\"message\":\"필수 구매 옵션 확인\",\"data\":null}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var rejected=writer.execute(1L,prepared,prepared.steps().getFirst(),prepared.mapping(),s->{});
        assertThat(rejected.state()).isEqualTo(State.FAILED);assertThat(rejected.mapping().sellerProductId()).isNull();
        clearInvocations(client);
        var next=writer.prepare(1L,document,rejected.mapping(),document,List.of());
        assertThat(next.steps()).extracting(Step::type).containsExactly(Type.CREATE,Type.APPROVAL);
        verify(client,never()).rawProduct(anyString());
        verify(client,never()).rawSummary(anyString());verify(client,never()).write(any(),any(),any(),any());
    }
    @Test
    void createThenApprovalPreservesAssignedIdentifiers() {
        var p = writer.prepare(1L, creation(), null, creation(), List.of());
        var actual = json.readTree(p.steps().getFirst().bodyJson()).deepCopy().asObject().put("sellerProductId", 555)
                .put("statusName", "승인완료");
        actual.path("items").get(0).asObject().put("sellerProductItemId", 111).put("vendorItemId", 222);
        when(client.rawProduct("555")).thenReturn(actual);
        when(client.write(any(), any(), any(), any()))
                .thenReturn(new CoupangHttpTransport.Response(200, "{\"code\":\"SUCCESS\",\"data\":555}".getBytes()));
        var saved = writer.execute(1L, p, p.steps().getFirst(), p.mapping(), ignored -> {
        });
        assertThat(saved.code()).isEqualTo("SAVED_FOR_APPROVAL");
        var approval = writer.execute(1L, p, p.steps().getLast(), saved.mapping(), ignored -> {
        });
        assertThat(approval.state()).isEqualTo(State.CONFIRMED);
        assertThat(approval.mapping().options().getFirst().vendorItemId()).isEqualTo("222");
        verify(client,never()).rawSummary(anyString());
        verify(client,never()).rawCategory(anyString());
        verify(client,times(1)).rawProduct("555");
        verify(client,never()).rawInventory(anyString());
    }
    Prepared creationReflectionFixture() {
        var tree=json.valueToTree(creation());var item=tree.path("options").get(0).asObject();
        tree.path("settings").asObject().put("manufacture","LF");
        item.path("registration").asObject().put("searchTags","헤지스, 여성, 가죽, 카드지갑, 블랙");
        item.set("contents",json.valueToTree(List.of(
                new CoupangWorkingCopies.Content(UUID.randomUUID().toString(),"IMAGE","IMAGE","https://assets.example.test/common/guide.png",null),
                new CoupangWorkingCopies.Content(UUID.randomUUID().toString(),"HTML","TEXT","<p>상품 안내</p><img src=\"https://assets.example.test/a.jpg\" alt=\"상품\">",null))));
        var document=json.treeToValue(tree,Document.class);
        return writer.prepare(1L,document,null,document,List.of());
    }
    ObjectNode reflectedCreation(Prepared p) {
        var result=json.readTree(p.steps().getFirst().bodyJson()).deepCopy().asObject()
                .put("sellerProductId",555).put("productId",999).put("statusName","승인완료");
        result.path("items").get(0).asObject().put("sellerProductItemId",111).put("vendorItemId",222);
        return result;
    }
    Result checkCreation(Prepared p,ObjectNode actual) {
        when(client.rawProduct("555")).thenReturn(actual);
        var assigned=new Mapping(mapping.accountKey(),"555",List.of(new OptionMapping(p.mapping().options().getFirst().optionId(),"111","222")));
        return writer.reconcile(1L,p,p.steps().getFirst(),new Result(State.ACCEPTED,assigned,"SAVED_FOR_APPROVAL","",client.now()));
    }
    @Test void creationConfirmationIgnoresDatesSearchOrderAndImageLocationRewritesWithoutChangingEvidence() {
        var p=creationReflectionFixture();var expected=p.steps().getFirst().bodyJson();var actual=reflectedCreation(p);
        actual.put("saleStartedAt","2026-10-21T20:44:35").put("saleEndedAt","2099-12-31T00:00:00");
        var item=actual.path("items").get(0).asObject();
        item.set("searchTags",json.valueToTree(List.of("가죽","카드지갑","블랙","여성","헤지스")));
        var image=item.path("images").get(0).asObject();image.put("vendorPath","02.jpg").put("cdnPath","https://image.example.test/cdn/changed.jpg");
        item.path("contents").get(0).path("contentDetails").get(0).asObject().put("content","vendor_inventory/converted-guide.png");
        item.path("contents").get(1).path("contentDetails").get(0).asObject().put("content","<p>상품 안내</p><img src=\"https://image.example.test/cdn/a.jpg\" alt=\"상품\">");
        var actualBefore=actual.deepCopy();var result=checkCreation(p,actual);
        assertThat(result.state()).isEqualTo(State.CONFIRMED);assertThat(result.code()).isEqualTo("CREATED_CONFIRMED");
        assertThat(result.mapping().sellerProductId()).isEqualTo("555");
        assertThat(actual).isEqualTo(actualBefore);assertThat(p.steps().getFirst().bodyJson()).isEqualTo(expected);
        verify(client,never()).write(any(),any(),any(),any());verify(client,never()).rawSummary(anyString());
    }
    @ParameterizedTest @ValueSource(strings={"missing-tag","changed-tag","duplicate-tag","price","original-price","name","manufacturer","attribute",
            "missing-image","image-type","image-order","empty-image-url","missing-content","content-type","empty-content-url","html-text","html-image-count","html-image-alt","html-data-src","null","blank","none"})
    void creationStillReportsOtherValueDifferencesAndMissingImageStructure(String change) {
        var p=creationReflectionFixture();var actual=reflectedCreation(p);var item=actual.path("items").get(0).asObject();
        var image=item.path("images").get(0).asObject();
        var parts=item.path("contents").get(1).path("contentDetails").get(0).asObject();
        switch(change) {
            case "missing-tag" -> item.path("searchTags").asArray().remove(0);
            case "changed-tag" -> item.path("searchTags").asArray().set(0,json.valueToTree("다른 검색어"));
            case "duplicate-tag" -> item.path("searchTags").asArray().set(0,item.path("searchTags").get(1));
            case "price" -> item.put("salePrice",130000);
            case "original-price" -> item.put("originalPrice",170000);
            case "name" -> actual.put("sellerProductName","변경한 이름");
            case "manufacturer" -> actual.put("manufacture","다른 제조사");
            case "attribute" -> item.path("attributes").get(0).asObject().put("attributeValueName","레드");
            case "missing-image" -> item.path("images").asArray().remove(0);
            case "image-type" -> image.put("imageType","DETAIL");
            case "image-order" -> image.put("imageOrder",5);
            case "empty-image-url" -> image.put("vendorPath","");
            case "missing-content" -> item.path("contents").asArray().remove(0);
            case "content-type" -> item.path("contents").get(0).path("contentDetails").get(0).asObject().put("detailType","TEXT");
            case "empty-content-url" -> item.path("contents").get(0).path("contentDetails").get(0).asObject().put("content","");
            case "html-text" -> parts.put("content","<p>다른 상품 안내</p><img src=\"https://image.example.test/cdn.jpg\" alt=\"상품\">");
            case "html-image-count" -> parts.put("content","<p>상품 안내</p>");
            case "html-image-alt" -> parts.put("content","<p>상품 안내</p><img src=\"https://image.example.test/cdn.jpg\" alt=\"다른 상품\">");
            case "html-data-src" -> parts.put("content","<p>상품 안내</p><img data-src=\"https://image.example.test/cdn.jpg\" alt=\"상품\">");
            case "null" -> item.putNull("salePrice");
            case "blank" -> item.put("salePrice"," ");
            case "none" -> item.put("salePrice","NONE");
        }
        var result=checkCreation(p,actual);
        assertThat(result.state()).isEqualTo(State.CONFIRMED);assertThat(result.code()).isEqualTo("CREATED_VALUES_DIFFER");
    }
    @Test void existingProductPeriodEditsStillRequireExactReflection() {
        raw.put("saleStartedAt","2026-10-15T00:00:00").put("saleEndedAt","2099-12-31T00:00:00");
        observed=CoupangInputs.from(client.editorDocument(raw,Map.of("222",new CoupangCatalog.CurrentInventory("222",120000L,9L,true)),Map.of()),mapping);
        var p=prepare(change("settings.saleEndedAt","2099-12-31T23:59:00"));
        assertThat(p.steps().getLast().type()).isEqualTo(Type.APPROVAL);
        var result=writer.reconcile(1L,p,p.steps().getLast(),new Result(State.ACCEPTED,mapping,"APPROVAL_PENDING","",client.now()));
        assertThat(result.state()).isEqualTo(State.ACCEPTED);
        assertThat(result.code()).isEqualTo("REFLECTION_PENDING");
    }
    @Test void existingProductApprovalKeepsTheCommonResponseGap() {
        var p=prepare(change("basic.sellerProductName","수정한 상품명"));
        assertThat(p.steps().getLast().type()).isEqualTo(Type.APPROVAL);
        writer.execute(1L,p,p.steps().getLast(),mapping,sent->{});
        verify(client,never()).withFirstResponseGap(any(),any());
        verify(client).write(eq("PUT"),endsWith("/123/approvals"),eq(""),isNull(),any(Runnable.class));
    }
    @Test void expiredRulesOrAChangedSnapshotCannotDispatchOrRepeatCreate() {
        var p=writer.prepare(1L,creation(),null,creation(),List.of());
        when(client.creationCategory("10")).thenThrow(new InputValidationFailure("카테고리 규격 만료"));
        var failed=writer.execute(1L,p,p.steps().getFirst(),p.mapping(),s->{});
        assertThat(failed.state()).isEqualTo(State.FAILED);
        verify(client,never()).write(any(),any(),any(),any(),any());
        var r=new CoupangEditor.CategoryRules("10",true,List.of(),List.of(),List.of(),List.of(),List.of(),"2026-10-15T00:01:00Z");
        doReturn(r).when(client).creationCategory("10");
        failed=writer.execute(1L,p,p.steps().getFirst(),p.mapping(),s->{});
        assertThat(failed.code()).isEqualTo("MAPPING_CHANGED");
        verify(client,never()).write(any(),any(),any(),any(),any());
    }
    @Test void acceptedApprovalKeepsLookingUpPendingStatusUntilConfirmedWithoutAnotherWrite() {
        var p=writer.prepare(1L,creation(),null,creation(),List.of());
        when(client.write(any(),any(),any(),any())).thenReturn(new CoupangHttpTransport.Response(200,"{\"code\":\"SUCCESS\",\"data\":555}".getBytes()));
        var saved=writer.execute(1L,p,p.steps().getFirst(),p.mapping(),s->{});
        var pending=json.readTree(p.steps().getFirst().bodyJson()).deepCopy().asObject().put("sellerProductId",555).put("statusName","승인대기중");
        pending.path("items").get(0).asObject().put("sellerProductItemId",111).put("vendorItemId",222);
        when(client.rawProduct("555")).thenReturn(pending,pending,reflectedCreation(p));
        var result=writer.execute(1L,p,p.steps().getLast(),saved.mapping(),s->{});
        assertThat(result.state()).isEqualTo(State.CONFIRMED);assertThat(result.code()).isEqualTo("CREATED_CONFIRMED");
        assertThat(result.mapping().sellerProductId()).isEqualTo("555");verify(client,times(3)).rawProduct("555");
        verify(client,times(2)).write(anyString(),anyString(),anyString(),nullable(String.class),any(Runnable.class));
        verify(client,never()).rawSummary(anyString());
    }
    @ParameterizedTest @ValueSource(strings={"NETWORK","AUTHENTICATION","CANCELLED"})
    void automaticApprovalLookupFailureKeepsAcceptedAndExplainsTheAttempt(String kind) {
        var p=writer.prepare(1L,creation(),null,creation(),List.of());
        var assigned=new Mapping(p.accountKey(),"555",p.mapping().options());
        when(client.write(eq("PUT"),endsWith("/555/approvals"),eq(""),isNull(),any(Runnable.class)))
                .thenReturn(new CoupangHttpTransport.Response(200,"{\"code\":\"SUCCESS\",\"data\":\"555\"}".getBytes()));
        when(client.rawProduct("555")).thenThrow(new CoupangFailure(CoupangFailure.Kind.valueOf(kind)));
        var result=writer.execute(1L,p,p.steps().getLast(),assigned,s->{});
        assertThat(result.state()).isEqualTo(State.ACCEPTED);
        assertThat(result.code()).isEqualTo("APPROVAL_LOOKUP_FAILED");
        assertThat(result.mapping()).isEqualTo(assigned);
        assertThat(result.message()).contains("자동 결과 조회", "다시 확인");
        verify(client).rawProduct("555");
        verify(client,times(2)).withFirstResponseGap(eq(Duration.ofSeconds(5)),any());
        verify(client,times(1)).write(anyString(),anyString(),anyString(),nullable(String.class),any(Runnable.class));
    }
    @Test void processingResponseAutomaticallyContinuesReadsAndPreservesConfirmedApproval() {
        var p=writer.prepare(1L,creation(),null,creation(),List.of());
        var assigned=new Mapping(p.accountKey(),"555",p.mapping().options());
        when(client.write(eq("PUT"),anyString(),eq(""),isNull(),any(Runnable.class)))
                .thenReturn(new CoupangHttpTransport.Response(200,"{\"code\":\"SUCCESS\",\"data\":555}".getBytes()));
        when(client.rawProduct("555")).thenThrow(new CoupangFailure(CoupangFailure.Kind.PRODUCT_PROCESSING))
                .thenThrow(new CoupangFailure(CoupangFailure.Kind.PRODUCT_PROCESSING)).thenReturn(reflectedCreation(p));
        var result=writer.execute(1L,p,p.steps().getLast(),assigned,s->{});
        assertThat(result.state()).isEqualTo(State.CONFIRMED);
        verify(client,times(3)).rawProduct("555");
        verify(client,times(4)).withFirstResponseGap(eq(Duration.ofSeconds(5)),any());
        verify(client,times(1)).write(anyString(),anyString(),anyString(),nullable(String.class),any(Runnable.class));
    }
    @Test void continuedApprovalChecksHaveABoundedBudgetAndNeverRepeatTheApproval() {
        var p=writer.prepare(1L,creation(),null,creation(),List.of());
        var assigned=new Mapping(p.accountKey(),"555",p.mapping().options());
        when(client.write(eq("PUT"),anyString(),eq(""),isNull(),any(Runnable.class)))
                .thenReturn(new CoupangHttpTransport.Response(200,"{\"code\":\"SUCCESS\",\"data\":555}".getBytes()));
        when(client.rawProduct("555")).thenThrow(new CoupangFailure(CoupangFailure.Kind.PRODUCT_PROCESSING));
        var result=writer.execute(1L,p,p.steps().getLast(),assigned,s->{});
        assertThat(result.state()).isEqualTo(State.ACCEPTED);
        assertThat(result.code()).isEqualTo("APPROVAL_CHECK_TIMEOUT");assertThat(result.mapping()).isEqualTo(assigned);
        verify(client,times(120)).rawProduct("555");
        verify(client,times(1)).write(anyString(),anyString(),anyString(),nullable(String.class),any(Runnable.class));
    }
    @Test void authorityLossDuringAutomaticChecksPreservesAcceptanceAndStopsReads() {
        var p=writer.prepare(1L,creation(),null,creation(),List.of());
        var assigned=new Mapping(p.accountKey(),"555",p.mapping().options());
        when(client.write(eq("PUT"),anyString(),eq(""),isNull(),any(Runnable.class)))
                .thenReturn(new CoupangHttpTransport.Response(200,"{\"code\":\"SUCCESS\",\"data\":555}".getBytes()));
        when(client.rawProduct("555")).thenAnswer(i->{
            doThrow(mock(BusinessException.class)).when(access).productActor(1L,true);
            throw new CoupangFailure(CoupangFailure.Kind.PRODUCT_PROCESSING);
        });
        var result=writer.execute(1L,p,p.steps().getLast(),assigned,s->{});
        assertThat(result.state()).isEqualTo(State.ACCEPTED);
        assertThat(result.code()).isEqualTo("APPROVAL_CHECK_ACCESS_DENIED");
        verify(client,times(1)).rawProduct("555");
    }
    @Test void retryingOnlyTheRejectedApprovalAutomaticallyLooksUpAfterFiveSecondGap() {
        var p=writer.prepare(1L,creation(),null,creation(),List.of());
        var assigned=new Mapping(p.accountKey(),"555",p.mapping().options());
        var approval=p.steps().getLast();
        when(client.write(eq("PUT"),endsWith("/555/approvals"),eq(""),isNull(),any(Runnable.class)))
                .thenReturn(new CoupangHttpTransport.Response(200,"{\"code\":\"ERROR\",\"message\":\"임시저장 상태의 상품만 승인 요청 가능합니다.\"}".getBytes()),
                        new CoupangHttpTransport.Response(200,"{\"code\":\"SUCCESS\",\"data\":\"555\"}".getBytes()));
        when(client.rawProduct("555")).thenReturn(reflectedCreation(p));
        var rejected=writer.execute(1L,p,approval,assigned,s->{});
        assertThat(rejected.state()).isEqualTo(State.FAILED);
        verify(client,never()).rawProduct(anyString());
        var retried=writer.execute(1L,p,approval,rejected.mapping(),s->{});
        assertThat(retried.state()).isEqualTo(State.CONFIRMED);
        assertThat(retried.code()).isEqualTo("CREATED_CONFIRMED");
        var order=inOrder(client);
        order.verify(client).write(eq("PUT"),endsWith("/555/approvals"),eq(""),isNull(),any(Runnable.class));
        order.verify(client).write(eq("PUT"),endsWith("/555/approvals"),eq(""),isNull(),any(Runnable.class));
        order.verify(client).rawProduct("555");
        // Two approval dispatches and the automatic result lookup each use the creation gap.
        verify(client,times(3)).withFirstResponseGap(eq(Duration.ofSeconds(5)),any());
        verify(client,times(1)).rawProduct("555");
        verify(client,never()).rawSummary(anyString());
        verify(client,never()).write(eq("POST"),anyString(),anyString(),nullable(String.class),any(Runnable.class));
    }
    @Test void unknownCreateWithOldSkuEvidenceNeverQueriesCandidatesOrWrites() {
        var p=writer.prepare(1L,creation(),null,creation(),List.of());
        var step=p.steps().getFirst();
        var old=new Step(step.id(),step.type(),step.optionId(),step.method(),step.path(),step.query(),step.bodyJson(),"{\"knownSellerProductIds\":[\"999\"]}",step.expectedJson());
        var result=writer.reconcile(1L,p,old,new Result(State.UNKNOWN,p.mapping(),"NETWORK","",client.now().minusSeconds(120)));
        assertThat(result.state()).isEqualTo(State.UNKNOWN);assertThat(result.message()).contains("Wing");
        verify(client,never()).rawSummary(anyString());verify(client,never()).rawProduct(anyString());
        verify(client,never()).write(any(),any(),any(),any(),any());
        assertThat(old.baselineJson()).contains("999");
    }
    @Test void creationUsesTheAlreadyReadOutboundPolicyForRegionalFees() {
        var tree=json.valueToTree(creation());tree.path("delivery").asObject().put("remoteAreaDeliverable","Y").put("jeju","3000").put("notJeju","5000");
        var d=json.treeToValue(tree,Document.class);
        var place=new CoupangShippingPlaces.Place("123","합성 출고지",true,List.of(),List.of(new CoupangShippingPlaces.Remote("CJGLS","3000","5000",true)));
        when(client.creationOutboundPlace("123")).thenReturn(place);
        var p=writer.prepare(1L,d,null,d,List.of());assertThat(p.steps()).hasSize(2);
        writer.execute(1L,p,p.steps().getFirst(),p.mapping(),s->{});
        verify(client,never()).rawOutboundPlace(anyString());
        when(client.creationOutboundPlace("123")).thenThrow(new InputValidationFailure("출고지 확인 만료"));
        assertThatThrownBy(()->writer.prepare(1L,d,null,d,List.of())).isInstanceOf(InputValidationFailure.class);
    }
    @Test void multipleCreationOptionsShareOneServerRulesSnapshot() {
        var tree=json.valueToTree(creation());var options=tree.path("options").asArray();
        var second=options.get(0).deepCopy().asObject().put("id",UUID.randomUUID().toString()).put("itemName","레드");
        second.path("attributes").get(0).asObject().put("value","레드");options.add(second);
        var d=json.treeToValue(tree,Document.class);var p=writer.prepare(1L,d,null,d,List.of());
        assertThat(json.readTree(p.steps().getFirst().bodyJson()).path("items").size()).isEqualTo(2);
        verify(client,times(1)).creationCategory("10");verify(client,never()).rawCategory(anyString());verify(client,never()).rawSummary(anyString());
    }
    @Test void createRetryGuardReusesRulesAndDoesNotSearchSameCodeProducts() {
        var p=writer.prepare(1L,creation(),null,creation(),List.of());
        doAnswer(i->{((Runnable)i.getArgument(4)).run();return new CoupangHttpTransport.Response(200,"{\"code\":\"SUCCESS\",\"data\":555}".getBytes());})
                .when(client).write(anyString(),anyString(),anyString(),nullable(String.class),any(Runnable.class));
        var result=writer.execute(1L,p,p.steps().getFirst(),p.mapping(),s->{});
        assertThat(result.code()).isEqualTo("SAVED_FOR_APPROVAL");
        verify(client,never()).rawSummary(anyString());verify(client,never()).rawCategory(anyString());
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void realClientAutomaticallyReadsUntilApprovedAndNeverRepeatsCreateOrApproval(boolean processing) {
        var transport=mock(CoupangHttpTransport.class);var calls=new ArrayList<String>();
        var starts=new HashMap<String,Long>();var finishes=new HashMap<String,Long>();
        var detailStarts=new ArrayList<Long>();var detailFinishes=new ArrayList<Long>();
        var sent=new java.util.concurrent.atomic.AtomicReference<ObjectNode>();
        when(transport.send(anyString(),anyString(),anyString(),anyString(),any(),any())).thenAnswer(i->{
            String method=i.getArgument(0),path=i.getArgument(1);calls.add(method+" "+path);
            starts.put(method+" "+path,System.nanoTime());
            boolean detailRead=method.equals("GET")&&path.endsWith("/555");
            if(detailRead)detailStarts.add(System.nanoTime());
            // A response slower than the start-to-start throttle exposes the missing post-response gap.
            if(method.equals("POST")||path.endsWith("/approvals"))Thread.sleep(1200);
            String response;
            if(path.contains("category-related-metas"))response="{\"code\":\"SUCCESS\",\"data\":{\"isAllowSingleItem\":true,\"attributes\":[{\"attributeTypeName\":\"색상\",\"exposed\":\"EXPOSED\",\"required\":\"OPTIONAL\",\"groupNumber\":\"NONE\",\"dataType\":\"STRING\"}],\"noticeCategories\":[],\"certifications\":[],\"requiredDocumentNames\":[],\"allowedOfferConditions\":[]}}";
            else if(method.equals("POST")&&path.equals(CoupangProductClient.PATH)){
                sent.set(json.readTree((byte[])i.getArgument(4)).asObject());response="{\"code\":\"SUCCESS\",\"data\":555}";
            }else if(path.endsWith("/approvals"))response="{\"code\":\"SUCCESS\",\"data\":555}";
            else if(method.equals("GET")&&path.endsWith("/555")){
                if(processing&&detailStarts.size()<=2){
                    detailFinishes.add(System.nanoTime());
                    return new CoupangHttpTransport.Response(400,"{\"code\":\"ERROR\",\"message\":\"상품 정보가 등록 또는 수정되고 있습니다.\"}".getBytes());
                }
                var detail=sent.get().deepCopy().put("sellerProductId",555)
                        .put("statusName",processing&&detailStarts.size()==3?"승인대기중":"승인완료");
                detail.path("items").get(0).asObject().put("sellerProductItemId",111).put("vendorItemId",222);
                response=json.writeValueAsString(json.createObjectNode().put("code","SUCCESS").set("data",detail));
            }else throw new AssertionError("Unexpected provider operation: "+method+" "+path);
            finishes.put(method+" "+path,System.nanoTime());
            if(detailRead)detailFinishes.add(System.nanoTime());
            return new CoupangHttpTransport.Response(200,response.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        });
        var real=new CoupangProductClient("A-test","synthetic-access","synthetic-secret",json,transport,Clock.systemUTC());
        org.springframework.test.util.ReflectionTestUtils.setField(real,"vendorUserId","server");
        real.category("10");
        var gateway=new DefaultCoupangWriteGateway(access,real,assets,json,mock(CoupangBrands.class),r2);
        var p=gateway.prepare(1L,creation(),null,creation(),List.of());
        var saved=gateway.execute(1L,p,p.steps().getFirst(),p.mapping(),s->{});
        assertThat(saved.code()).isEqualTo("SAVED_FOR_APPROVAL");assertThat(saved.mapping().sellerProductId()).isEqualTo("555");
        var approved=gateway.execute(1L,p,p.steps().getLast(),saved.mapping(),s->{});
        assertThat(approved.state()).isEqualTo(State.CONFIRMED);
        assertThat(approved.mapping().options().getFirst().vendorItemId()).isEqualTo("222");
        var expectedCalls=new ArrayList<>(List.of(
                "GET /v2/providers/seller_api/apis/api/v1/marketplace/meta/category-related-metas/display-category-codes/10",
                "POST "+CoupangProductClient.PATH,"PUT "+CoupangProductClient.PATH+"/555/approvals","GET "+CoupangProductClient.PATH+"/555"));
        if(processing)for(int i=0;i<3;i++)expectedCalls.add("GET "+CoupangProductClient.PATH+"/555");
        assertThat(calls).containsExactlyElementsOf(expectedCalls);
        assertThat(starts.get("PUT "+CoupangProductClient.PATH+"/555/approvals")-finishes.get("POST "+CoupangProductClient.PATH))
                .as("registration response to approval dispatch").isGreaterThanOrEqualTo(5_000_000_000L);
        assertThat(detailStarts.getFirst()-finishes.get("PUT "+CoupangProductClient.PATH+"/555/approvals"))
                .as("approval response to automatic result lookup").isGreaterThanOrEqualTo(5_000_000_000L);
        if(processing){
            assertThat(detailStarts.get(1)-detailFinishes.get(0)).as("common transport retry").isGreaterThanOrEqualTo(2_000_000_000L);
            for(int i=2;i<detailStarts.size();i++)assertThat(detailStarts.get(i)-detailFinishes.get(i-1))
                    .as("next automatic result lookup").isGreaterThanOrEqualTo(5_000_000_000L);
        }
    }
    @Test
    void incompleteCreationCannotPrepareButCanRemainALocalDocument() {
        var d = CoupangInputs.empty();
        assertThat(CoupangWorkingCopyDocuments.normalize(d, json)).isNotNull();
        assertThatThrownBy(() -> writer.prepare(1L, d, null, d, List.of())).isInstanceOf(InputValidationFailure.class);
        verify(client, never()).write(any(), any(), any(), any());
    }
    @Test
    void missingWingIdentityStopsCreateBeforeDispatch() {
        when(client.registrationUserId()).thenReturn("");
        assertThatThrownBy(() -> writer.prepare(1L, creation(), null, creation(), List.of()))
                .isInstanceOf(InputValidationFailure.class);
        verify(client, never()).write(any(), any(), any(), any());
    }
    @Test
    void combinedShippingAndInformationChangesApplyShippingBeforeSaving() {
        var p = prepare(change("basic.sellerProductName", "mine"), change("delivery.returnCharge", "4000"));
        assertThat(p.steps()).extracting(Step::type).containsExactly(Type.DELIVERY, Type.PRODUCT, Type.APPROVAL);
    }

    @Test
    void anInitiallyMissingVendorIdMayBeFilledOnlyFromTheSameServerOption() {
        var prior = new Mapping(mapping.accountKey(), "123", List.of(new OptionMapping(option, "111", null)));
        var c = change("options.registration.salePrice", "130000");
        var wanted = CoupangInputs.apply(json, observed, List.of(c));
        var p = writer.prepare(1L, wanted, prior, observed, List.of(c));
        assertThat(p.mapping().options().getFirst().vendorItemId()).isEqualTo("222");
        assertThat(p.steps()).extracting(Step::type).containsExactly(Type.PRICE);
    }

    @Test
    void unpersistedRegisteredProductSessionsKeepTheSameOptionUuid() {
        var source = client.editorDocument(raw, Map.of(), Map.of());
        var a = CoupangInputs.mapping(mapping.accountKey(), source, null);
        var b = CoupangInputs.mapping(mapping.accountKey(), source, null);
        assertThat(a).isEqualTo(b);
    }

    @Test
    void aMissingConfiguredAccountCannotOwnALocalWorkingCopy() {
        when(client.vendorId()).thenReturn("");
        assertThat(writer.accountKey()).isNull();
    }

    @Test
    void editedImageListKeepsConfirmedInputsWhenRemoteImagesHaveChanged() {
        var original = observed.options().getFirst().images().getFirst();
        var desired = List.of(original, new CoupangWorkingCopies.Image(UUID.randomUUID().toString(), null,
                "https://assets.test/new.jpg", "DETAIL", 1));
        raw.path("items").get(0).path("images").get(0).asObject()
                .put("vendorPath", "https://image.example.test/external-change.jpg").put("futureImage", "preserved");
        var p = prepare(change("options.images", desired));
        var images = json.readTree(p.steps().getFirst().bodyJson()).path("items").get(0).path("images");
        assertThat(images.get(0).path("vendorPath").asString()).isEqualTo(original.url());
        assertThat(images.get(0).path("futureImage").asString()).isEqualTo("preserved");
        assertThat(images).hasSize(2);
    }
    @Test
    void editedDescriptionKeepsPreviouslyObservedImageReferences() {
        String prior = "<p>old</p><img src=\"https://image.example.test/original.jpg\">";
        raw.path("items").get(0).path("contents").get(0).path("contentDetails").get(0).asObject().put("content", prior);
        observed = CoupangInputs.from(client.editorDocument(raw, Map.of(), Map.of()), mapping);
        var old = observed.options().getFirst().contents().getFirst();
        String wanted = prior.replace("old", "my edit");
        raw.path("items").get(0).path("contents").get(0).path("contentDetails").get(0).asObject().put("content",
                "<p>external</p>");
        var edit = new CoupangWorkingCopies.Content(old.id(), old.type(), old.detailType(), wanted, null);
        var p = prepare(change("options.contents", List.of(edit)));
        assertThat(json.readTree(p.steps().getFirst().bodyJson()).path("items").get(0).path("contents").get(0)
                .path("contentDetails").get(0).path("content").asString()).isEqualTo(wanted);
        verify(r2).validateDescription(eq(1L), eq(wanted), contains("https://image.example.test/original.jpg"));
    }

    @Test
    void officialDescriptionTypesOpenWithoutChangingTheirIdentityOrSource() {
        var contents = raw.path("items").get(0).asObject().putArray("contents");
        for (String type : List.of("HTML", "IMAGE", "IMAGE_NO_SPACE", "TEXT", "IMAGE_TEXT", "TEXT_IMAGE",
                "IMAGE_IMAGE", "TEXT_TEXT", "TITLE")) {
            var row = contents.addObject().put("contentsType", type).put("future", "keep");
            var parts = row.putArray("contentDetails");
            parts.addObject().put("detailType", "TEXT").put("content", "same");
            parts.addObject().put("detailType", "IMAGE").put("content", "https://image.example.test/a.jpg");
        }
        var before = raw.deepCopy();
        observed = CoupangInputs.from(client.editorDocument(raw, Map.of(), Map.of()), mapping);
        var normalized = CoupangWorkingCopyDocuments.normalize(observed, json);
        assertThat(normalized.options().getFirst().contents()).hasSize(18);
        assertThat(normalized.options().getFirst().contents().stream().map(CoupangWorkingCopies.Content::id))
                .doesNotHaveDuplicates();
        var p = prepare(change("basic.sellerProductName", "changed"));
        assertThat(json.readTree(p.steps().getFirst().bodyJson()).path("items").get(0).path("contents"))
                .isEqualTo(before.path("items").get(0).path("contents"));
    }

    @Test
    void editingSpaceFreeDescriptionKeepsTheCoupangTypeAndUnchangedRowFields() {
        var row = raw.path("items").get(0).path("contents").get(0).asObject();
        row.put("contentsType", "IMAGE_NO_SPACE").put("future", "preserved");
        row.path("contentDetails").get(0).asObject().put("detailType", "IMAGE")
                .put("content", "https://image.example.test/original.jpg").put("futureDetail", "preserved");
        observed = CoupangInputs.from(client.editorDocument(raw, Map.of(), Map.of()), mapping);
        var old = observed.options().getFirst().contents().getFirst();
        var selected = new CoupangWorkingCopies.Content(old.id(), old.type(), old.detailType(),
                "https://image.example.test/changed.jpg", null);
        var p = prepare(change("options.contents", List.of(selected)));
        var sent = json.readTree(p.steps().getFirst().bodyJson()).path("items").get(0).path("contents").get(0);
        assertThat(sent.path("contentsType").asString()).isEqualTo("IMAGE_NO_SPACE");
        assertThat(sent.path("future").asString()).isEqualTo("preserved");
        assertThat(sent.path("contentDetails").get(0).path("futureDetail").asString()).isEqualTo("preserved");
    }

    @Test
    void rowEditsPreserveExternalSizeAcrossPreparationAndDispatch() {
        var attrs = raw.path("items").get(0).path("attributes").asArray();
        attrs.get(0).asObject().put("editable", true);
        attrs.add(json.readTree(
                "{\"attributeTypeName\":\"사이즈\",\"attributeValueName\":\"M\",\"exposed\":\"EXPOSED\",\"editable\":true}"));
        observed = CoupangInputs.from(client.editorDocument(raw, Map.of(), Map.of()), mapping);
        var edit = new CoupangEditing.Change("options.attributes.value", option, "화이트", List.of("색상", "EXPOSED"),
                "SET");
        attrs.get(1).asObject().put("attributeValueName", "L");
        var p = prepare(edit);
        assertThat(p.schemaVersion()).isEqualTo(4);
        attrs.get(1).asObject().put("attributeValueName", "XL");
        attrs.get(0).asObject().put("attributeValueName", "외부");
        var sent = new ArrayList<Step>();
        writer.execute(1L, p, p.steps().getFirst(), mapping, sent::add);
        var actual = json.readTree(sent.getFirst().bodyJson()).path("items").get(0).path("attributes");
        assertThat(actual.get(0).path("attributeValueName").asString()).isEqualTo("화이트");
        assertThat(actual.get(1).path("attributeValueName").asString()).isEqualTo("XL");
        assertThat(actual.get(0).path("future").asBoolean()).isTrue();
    }
    @Test
    void historicalThreeFieldChangesKeepTheirOriginalSerialization() {
        assertThat(json.writeValueAsString(new CoupangEditing.Change("basic.brand", null, "brand")))
                .isEqualTo("{\"path\":\"basic.brand\",\"optionId\":null,\"value\":\"brand\"}");
    }
    @Test
    void legacyWholeListIntentsCannotPrepareNewWrites() {
        assertThatThrownBy(() -> prepare(change("options.attributes", observed.options().getFirst().attributes())))
                .hasMessageContaining("이전 목록");
    }
    @Test
    void schemaThreeRemainsReadableButCannotWrite() {
        var p = prepare(change("basic.sellerProductName", "mine"));
        var old = new Prepared(p.accountKey(), p.mapping(), p.steps(), p.changes(), p.preparedAt(), p.expectedSkus(),
                p.editIntent(), 3);
        assertThat(old.supported()).isTrue();
        assertThat(writer.execute(1L, old, old.steps().getFirst(), mapping, ignored -> {
        }).code()).isEqualTo("LEGACY_INTENT");
        verify(client, never()).write(any(), any(), any(), any());
    }

    @Test
    void aDocumentEditPreservesEveryUntouchedRawDocumentField() {
        raw.set("requiredDocuments", json.readTree(
                """
                        [{"templateName":"A","documentPath":"https://cdn.example.test/a","vendorDocumentPath":"https://origin.example.test/a","future":{"keep":true}},
                         {"templateName":"B","vendorDocumentPath":"https://origin.example.test/b"}]
                        """));
        observed = CoupangInputs.from(client.editorDocument(raw, Map.of(), Map.of()), mapping);
        var edit = new CoupangEditing.Change("documents.vendorPath", null, "https://origin.example.test/new",
                List.of("B"), "SET");
        var p = prepare(edit);
        var docs = json.readTree(p.steps().getFirst().bodyJson()).path("requiredDocuments");
        assertThat(docs.get(0)).isEqualTo(raw.path("requiredDocuments").get(0));
        assertThat(docs.get(1).path("vendorDocumentPath").asString()).isEqualTo(edit.value());
    }
    @Test
    void editingCertificationCodeDoesNotAddMissingFieldsToOtherRows() {
        raw.path("items").get(0).asObject().set("certifications", json.readTree(
                """
                        [{"certificationType":"TEST","certificationCode":"old"},{"certificationType":"NOT_REQUIRED","future":true}]
                        """));
        observed = CoupangInputs.from(client.editorDocument(raw, Map.of(), Map.of()), mapping);
        var edit = new CoupangEditing.Change("options.certifications.code", option, "mine", List.of("TEST"), "SET");
        var p = prepare(edit);
        var rows = json.readTree(p.steps().getFirst().bodyJson()).path("items").get(0).path("certifications");
        assertThat(rows.get(0).path("certificationCode").asString()).isEqualTo("mine");
        assertThat(rows.get(1)).isEqualTo(raw.path("items").get(0).path("certifications").get(1));
    }

    @Test
    void listRemovalPreservesUnknownFieldsAndExternalAddedRows() {
        raw.path("items").get(0).asObject().set("notices", json.readTree("""
                [{"noticeCategoryName":"C","noticeCategoryDetailName":"A","content":"a"},
                 {"noticeCategoryName":"C","noticeCategoryDetailName":"B","future":true}]
                """));
        observed = CoupangInputs.from(client.editorDocument(raw, Map.of(), Map.of()), mapping);
        var edit = new CoupangEditing.Change("options.notices", option, null, List.of("C", "A"), "REMOVE");
        raw.path("items").get(0).path("notices").asArray().add(json.readTree(
                "{\"noticeCategoryName\":\"C\",\"noticeCategoryDetailName\":\"new\",\"content\":\"remote\",\"future\":true}"));
        var p = prepare(edit);
        var rows = json.readTree(p.steps().getFirst().bodyJson()).path("items").get(0).path("notices");
        assertThat(rows.size()).isEqualTo(2);
        assertThat(rows.get(0)).isEqualTo(raw.path("items").get(0).path("notices").get(1));
        assertThat(rows.get(1)).isEqualTo(raw.path("items").get(0).path("notices").get(2));
    }
    @Test
    void explicitDocumentFieldsAreAppliedIndependently() {
        raw.set("requiredDocuments", json.readTree(
                "[{\"templateName\":\"A\",\"documentPath\":\"cdn-relative\",\"vendorDocumentPath\":\"https://origin.example.test/a\"}]"));
        observed = CoupangInputs.from(client.editorDocument(raw, Map.of(), Map.of()), mapping);
        var edit = new CoupangEditing.Change("documents.vendorPath", null, "https://origin.example.test/new",
                List.of("A"), "SET");
        var p = prepare(edit);
        var row = json.readTree(p.steps().getFirst().bodyJson()).path("requiredDocuments").get(0);
        assertThat(row.path("documentPath").asString()).isEqualTo("cdn-relative");
        assertThat(row.path("vendorDocumentPath").asString()).isEqualTo(edit.value());
    }

}
