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
        when(client.writeSession(any())).thenAnswer(i -> ((Supplier<?>) i.getArgument(0)).get());
        when(client.rawProduct("123")).thenAnswer(i -> raw.deepCopy());
        when(client.rawInventory(anyString(), anyBoolean(), anyBoolean())).thenAnswer(i -> inventory.deepCopy());
        when(client.rawInventory(anyString())).thenAnswer(i -> inventory.deepCopy());
        when(client.rawSummary(anyString())).thenReturn(json.createArrayNode());
        when(client.rawCategory(anyString())).thenReturn(new CoupangEditor.CategoryRules("10", true, List
                .of(new CoupangEditor.AttributeRule("색상", "EXPOSED", "OPTIONAL", "NONE", "STRING", null, List.of())),
                List.of(), List.of(), List.of(), List.of()));
        when(r2.resolve(anyLong(), anyString()))
                .thenAnswer(i -> new R2Images.Image("fixture", i.getArgument(1), "image/png", 500, 500, 100));
        when(client.write(anyString(), anyString(), anyString(), nullable(String.class)))
                .thenReturn(new CoupangHttpTransport.Response(200, "{\"code\":\"SUCCESS\"}".getBytes()));
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
    @Test
    void attributesWithTheSameNameUseExposureAsPartOfTheirIdentity() {
        var rows = raw.path("items").get(0).asObject().putArray("attributes");
        rows.addObject().put("attributeTypeName", "색상").put("attributeValueName", "블랙").put("exposed", "EXPOSED")
                .put("editable", true).put("future", "purchase");
        rows.addObject().put("attributeTypeName", "색상").put("attributeValueName", "검색값").put("exposed", "NONE")
                .put("editable", false).put("future", "search");
        observed = CoupangInputs.from(client.editorDocument(raw,
                Map.of("222", new CoupangCatalog.CurrentInventory("222", 120000L, 9L, true)), Map.of()), mapping);
        var edit = new CoupangEditing.Change("options.attributes.value", option, "브라운", List.of("색상", "EXPOSED"),
                "SET");
        var p = prepare(edit);
        rows.get(1).asObject().put("attributeValueName", "최신 검색값");
        var sent = new ArrayList<Step>();
        assertThat(writer.execute(1L, p, p.steps().getFirst(), mapping, sent::add).state()).isEqualTo(State.CONFIRMED);
        var wire = json.readTree(sent.getFirst().bodyJson()).path("items").get(0).path("attributes");
        assertThat(wire.get(0).path("attributeValueName").asString()).isEqualTo("브라운");
        assertThat(wire.get(1)).isEqualTo(rows.get(1));
        rows.get(0).asObject().put("editable", false);
        rows.get(1).asObject().put("editable", true);
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
