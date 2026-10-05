package cc.ataglace.molebutter.identity.api;
public interface UserPasswordChangeService {
    cc.ataglace.molebutter.identity.api.UserAccount changePassword(Long userId, String currentPassword, String newPassword);
}
