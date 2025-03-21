/*
 * Copyright The Athenz Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.yahoo.athenz.common.server.notification;

public class NotificationDomainMeta {

    private String domainName;
    private String slackChannel;

    public NotificationDomainMeta(String domainName) {
        this.domainName = domainName;
    }

    public String getDomainName() {
        return domainName;
    }

    public NotificationDomainMeta setSlackChannel(String slackChannel) {
        this.slackChannel = slackChannel;
        return this;
    }

    public String getSlackChannel() {
        return slackChannel;
    }
}
