package cc.ataglace.molebutter.coupang.internal;
import java.util.*;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.coupang.api.CoupangRegisteredProducts.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class OptionHistoryTest {
    final ObjectMapper json=new ObjectMapper();
    Event event(String id,String kind,String subject,String path,String before,String after,String execution){return new Event(id,kind,subject,path,before,after,1L,null,execution,"2026-10-16T00:00:00Z");}
    @Test void filtersExactOptionAndFieldAndProjectsWholeDocumentsWithoutLeakingOtherValues(){
        String before="{\"options\":[{\"id\":\"uuid\",\"registration\":{\"salePrice\":\"100\",\"maximumBuyCount\":\"0\"}},{\"id\":\"other\",\"registration\":{\"salePrice\":\"777\"}}]}";
        String after=before.replace("\"100\"","\"200\"").replace("\"777\"","\"999\"");
        var events=List.of(event("1","INVENTORY","vendor","$",null,"{\"data\":{\"salePrice\":100,\"amountInStock\":0}}",null),
            event("2","INVENTORY","different","$/salePrice","1","999",null),event("3","INVENTORY","vendor","$/amountInStock","0","144",null),
            event("4","WORKING_COPY_SAVE","copy","document",before,after,null));
        var price=OptionHistory.project(json,events,"vendor","uuid","copy","PRICE");assertThat(price).extracting(OptionEvent::id).containsExactly("4","1");assertThat(price.getFirst().before()).isEqualTo(100);assertThat(price.getFirst().after()).isEqualTo(200);
        var stock=OptionHistory.project(json,events,"vendor","uuid","copy","STOCK");assertThat(stock).extracting(OptionEvent::id).containsExactly("3","1");assertThat(stock.getFirst().after()).isEqualTo(144);
        assertThat(OptionHistory.project(json,events,"vendor",null,null,"PRICE")).extracting(OptionEvent::id).containsExactly("1");
    }
    @Test void requestAcceptedUnknownAndConfirmedStayDistinctAndCarryOnlySelectedField(){
        var events=new ArrayList<Event>();events.add(event("1","EXECUTION_REQUEST","uuid","PRICE","{\"salePrice\":100,\"amountInStock\":99}","{\"salePrice\":200}","execution"));
        events.add(event("2","EXECUTION_ACCEPTED","uuid","PRICE",null,"{}","execution"));events.add(event("3","EXECUTION_UNKNOWN","uuid","PRICE",null,"{}","execution"));events.add(event("4","EXECUTION_CONFIRMED","uuid","PRICE",null,"{}","execution"));events.add(event("5","EXECUTION_REQUEST","other","PRICE","{}","{}","different"));
        var result=OptionHistory.project(json,events,"vendor","uuid","copy","PRICE");assertThat(result).extracting(OptionEvent::kind).containsExactly("EXECUTION_CONFIRMED","EXECUTION_UNKNOWN","EXECUTION_ACCEPTED","EXECUTION_REQUEST");assertThat(result).allMatch(e->e.before()==100&&e.after()==200);
        assertThat(OptionHistory.project(json,events,"vendor","uuid","copy","STOCK")).isEmpty();
    }
}
