package io.mosip.keymanager.hsm.impl;

import java.io.IOException;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;

/**
 * AWS CloudHSM Application Callback handler for HSM Login.
 * 
 * @author Mahammed Taheer
 * @since 
 *
 */
public class ApplicationCallBackHandler implements CallbackHandler {
    
    private String cloudhsmPin = null;

    public ApplicationCallBackHandler(String pin) {
        cloudhsmPin = pin;
    }

    @Override
    public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException {
        for (int i = 0; i < callbacks.length; i++) {
            if (callbacks[i] instanceof PasswordCallback) {
                PasswordCallback pc = (PasswordCallback)callbacks[i];
                pc.setPassword(cloudhsmPin.toCharArray());
            }
        }
    }
}
