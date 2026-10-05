package cc.ataglace.molebutter.common.api;


/** Name and optimistic revision shared by named business settings. */
public record NamedSettingInput(String name, Long revision) {}
