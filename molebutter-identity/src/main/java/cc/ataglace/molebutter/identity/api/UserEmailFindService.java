package cc.ataglace.molebutter.identity.api;
public interface UserEmailFindService {
    String findMaskedEmail(String rawName, String rawPhoneNumber);
}
