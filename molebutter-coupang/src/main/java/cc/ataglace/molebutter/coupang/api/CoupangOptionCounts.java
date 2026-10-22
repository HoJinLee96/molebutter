package cc.ataglace.molebutter.coupang.api;
/** Account-scoped DB-only option count for exact normalized seller codes. */
public interface CoupangOptionCounts { int byCode(Long actor,String code); }
