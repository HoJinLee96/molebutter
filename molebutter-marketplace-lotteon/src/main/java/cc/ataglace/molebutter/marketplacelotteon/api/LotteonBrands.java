package cc.ataglace.molebutter.marketplacelotteon.api;

import java.util.List;

/** Read-only LOTTEON attribute catalog. This does not enable product registration. */
public interface LotteonBrands {
    /** At most the documented default page of 100 candidates; narrow the name to refine. */
    List<Brand> search(Long actor,String name);
    record Brand(String id,String name) {}
}
