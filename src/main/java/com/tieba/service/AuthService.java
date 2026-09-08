package com.tieba.service;
/*登录注册*/
import com.tieba.model.User;
import com.tieba.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.regex.Pattern;

@Service
public class AuthService {
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private final UserRepository users;
    private static final Pattern PASSWORD = Pattern.compile("^\\S{8,32}$");
    public AuthService(UserRepository users) { this.users = users; }
    public User login(String account, String password, boolean admin) {
        User user = users.findByAccount(account);
        if (user == null || user.banned() || !password.equals(users.password(account)) || (admin && !"ADMIN".equals(user.userType())) || (!admin && "ADMIN".equals(user.userType()))) { log.warn("登录失败 account={}, admin={}", account, admin); throw new IllegalArgumentException("账号或密码错误或账号已封禁"); }
        log.info("登录成功 account={}, type={}", account, user.userType());
        return user;
    }
    public void register(String account, String password, String username) {
        int categories = 0;
        if (password != null && password.matches(".*[a-z].*")) categories++;
        if (password != null && password.matches(".*[A-Z].*")) categories++;
        if (password != null && password.matches(".*\\d.*")) categories++;
        if (password != null && password.matches(".*[^A-Za-z0-9].*")) categories++;
        if (!PASSWORD.matcher(password == null ? "" : password).matches() || categories < 2) throw new IllegalArgumentException("密码需8-32位，大小写字母、数字、特殊字符至少包含两类，且不能有空格");
        if (users.findByAccount(account) != null) throw new IllegalArgumentException("账号已存在");
        users.insert(account, password, username);
        log.info("注册成功 account={}", account);
    }
}
