import java.io.BufferedReader;
import java.io.InputStreamReader;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Prints a BCrypt hash (cost 12) of a password, for AUTH_PASSWORD_HASH. Reads the password from the terminal
 * without echo (or, when there is no terminal, from the first line of standard input), so it never lands in a
 * shell history or a process list. Run from backend/ (see README, Authentication).
 */
public class HashPassword {

	public static void main(String[] args) throws Exception {
		String password;
		if (System.console() != null) {
			char[] typed = System.console().readPassword("Password: ");
			char[] again = System.console().readPassword("Repeat: ");
			if (typed == null || !java.util.Arrays.equals(typed, again)) {
				System.err.println("The two entries differ. Nothing was generated.");
				System.exit(1);
			}
			password = new String(typed);
		}
		else {
			password = new BufferedReader(new InputStreamReader(System.in)).readLine();
		}
		if (password == null || password.length() < 12) {
			System.err.println("Use a password of at least 12 characters.");
			System.exit(1);
		}
		if (password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
			System.err.println("BCrypt only uses the first 72 bytes; use a shorter password.");
			System.exit(1);
		}
		System.out.println(new BCryptPasswordEncoder(12).encode(password));
	}

}
