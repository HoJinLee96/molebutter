package cc.ataglace.molebutter.dto;

/** Name and optimistic revision shared by named business settings. */
public record NamedSettingInput(String name, Long revision) {}
