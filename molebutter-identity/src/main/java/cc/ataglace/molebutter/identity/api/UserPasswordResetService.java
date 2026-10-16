package cc.ataglace.molebutter.identity.api;
public interface UserPasswordResetService {
    void sendResetCode(String rawEmail, ClientInfo client);
    void verifyResetCode(String rawEmail, String code);
    void resetPassword(String rawEmail, String newPassword, ClientInfo client);
}
