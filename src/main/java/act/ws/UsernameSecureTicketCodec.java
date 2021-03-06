package act.ws;

/*-
 * #%L
 * ACT Framework
 * %%
 * Copyright (C) 2014 - 2017 ActFramework
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import act.Act;
import act.app.util.AppCrypto;
import act.conf.AppConfig;

/**
 * A simple implementation of {@link SecureTicketCodec} that process
 * `id` and `username` in session only
 */
public class UsernameSecureTicketCodec extends StringSecureTicketCodec {

    public UsernameSecureTicketCodec() {
        super(Act.appConfig().sessionKeyUsername());
    }

    public UsernameSecureTicketCodec(AppConfig config) {
        super(config.sessionKeyUsername());
    }

    public UsernameSecureTicketCodec(String sessinKeyUsername) {
        super(sessinKeyUsername);
    }


    public UsernameSecureTicketCodec(AppCrypto crypto) {
        super(crypto, Act.appConfig().sessionKeyUsername());
    }

    public UsernameSecureTicketCodec(AppCrypto crypto, AppConfig config) {
        super(crypto, config.sessionKeyUsername());
    }

    public UsernameSecureTicketCodec(AppCrypto crypto, String sessinKeyUsername) {
        super(crypto, sessinKeyUsername);
    }


}
