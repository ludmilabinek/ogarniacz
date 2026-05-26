package com.example.app.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class SignupForm {

    @NotBlank
    @Email
    @Size(max = 254)
    private String email;

    @NotBlank
    @Size(min = 12, max = 100, message = "Password must be at least 12 characters.")
    private String password;

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
