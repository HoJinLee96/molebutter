package cc.ataglace.molebutter.imaging.internal.service;
import static org.assertj.core.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import cc.ataglace.molebutter.imaging.api.*;

class ImagingInputsTest {
    @Test void requiresRealActorProductAndSupportedBrand(){
        assertThatThrownBy(()->ImagingInputs.actor(null)).isInstanceOf(ImagingFailure.class);
        assertThatThrownBy(()->ImagingInputs.actor(0L)).isInstanceOf(ImagingFailure.class);
        assertThatThrownBy(()->ImagingInputs.product("../../","DAKS")).isInstanceOf(ImagingFailure.class);
        assertThatThrownBy(()->ImagingInputs.product("BAG1","UNKNOWN")).isInstanceOf(ImagingFailure.class);
    }
    @Test void acceptsManualCmAndRejectsNonFiniteOrInvalidDimensions(){
        ImagingInputs.selection(new SizeGuideSelectionDto("TEMPLATE","TOTE",null,null,new SizeDimensionsDto("22cm",".5","-")));
        for(String invalid:List.of("NaN","Infinity","0","-2","10001","1e999","22\ncm"))assertThatThrownBy(()->ImagingInputs.selection(new SizeGuideSelectionDto("TEMPLATE","TOTE",null,null,new SizeDimensionsDto(invalid,"12","22")))).isInstanceOf(ImagingFailure.class);
    }
    @Test void rejectsInfinitePhotoScaleAndCoordinatesBeforeRender(){
        var photo=new SizeGuideLayoutDto.PhotoPlacement(Double.POSITIVE_INFINITY,.5,.5);
        assertThatThrownBy(()->ImagingInputs.selection(new SizeGuideSelectionDto("PHOTO","TOTE",null,new SizeGuideLayoutDto(photo,null,null,null),null))).isInstanceOf(ImagingFailure.class);
        var arrow=new SizeGuideLayoutDto.ArrowPlacement(Double.NaN,0,1,1,.5,.5);
        assertThatThrownBy(()->ImagingInputs.selection(new SizeGuideSelectionDto("PHOTO","TOTE",null,new SizeGuideLayoutDto(null,arrow,null,null),null))).isInstanceOf(ImagingFailure.class);
    }
}
