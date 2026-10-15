package cc.ataglace.molebutter.app.internal;

import java.util.List;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import cc.ataglace.molebutter.identity.api.UserPrincipal;
import cc.ataglace.molebutter.marketplacelotteon.api.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public final class LotteonBrandController {
    private final LotteonBrands brands;
    public LotteonBrandController(LotteonBrands brands){this.brands=brands;}
    @GetMapping("/api/marketplaces/lotteon/metadata/brands")
    public ApiResponse<List<LotteonBrands.Brand>> search(@AuthenticationPrincipal UserPrincipal actor,@RequestParam String name){
        return ApiResponse.success(brands.search(actor.userId(),name));
    }
    @ExceptionHandler(InputValidationFailure.class)
    public ResponseEntity<ApiResponse<Void>> invalid(InputValidationFailure failure){
        return ResponseEntity.badRequest().body(ApiResponse.error(HttpStatus.BAD_REQUEST,"INVALID_INPUT",failure.getMessage()));
    }
    @ExceptionHandler(LotteonBrandFailure.class)
    public ResponseEntity<ApiResponse<Void>> external(LotteonBrandFailure failure){
        HttpStatus status=switch(failure.kind()){
            case CONFIGURATION->HttpStatus.SERVICE_UNAVAILABLE;
            case BUSY,CANCELLED->HttpStatus.CONFLICT;
            case RATE_LIMIT->HttpStatus.TOO_MANY_REQUESTS;
            case TIMEOUT->HttpStatus.GATEWAY_TIMEOUT;
            default->HttpStatus.BAD_GATEWAY;
        };
        return ResponseEntity.status(status).body(ApiResponse.error(status,"LOTTEON_"+failure.kind(),failure.getMessage()));
    }
}
