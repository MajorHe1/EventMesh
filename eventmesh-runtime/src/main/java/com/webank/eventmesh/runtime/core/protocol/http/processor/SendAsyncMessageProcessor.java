/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.webank.eventmesh.runtime.core.protocol.http.processor;

import com.webank.eventmesh.runtime.boot.ProxyHTTPServer;
import com.webank.eventmesh.runtime.constants.ProxyConstants;
import com.webank.eventmesh.runtime.core.protocol.http.async.AsyncContext;
import com.webank.eventmesh.runtime.core.protocol.http.async.CompleteHandler;
import com.webank.eventmesh.runtime.core.protocol.http.processor.inf.HttpRequestProcessor;
import com.webank.eventmesh.runtime.core.protocol.http.producer.ProxyProducer;
import com.webank.eventmesh.runtime.core.protocol.http.producer.SendMessageContext;
import com.webank.eventmesh.common.Constants;
import com.webank.eventmesh.common.IPUtil;
import com.webank.eventmesh.common.LiteMessage;
import com.webank.eventmesh.common.command.HttpCommand;
import com.webank.eventmesh.common.protocol.http.body.message.SendMessageRequestBody;
import com.webank.eventmesh.common.protocol.http.body.message.SendMessageResponseBody;
import com.webank.eventmesh.common.protocol.http.common.ProxyRetCode;
import com.webank.eventmesh.common.protocol.http.common.RequestCode;
import com.webank.eventmesh.common.protocol.http.header.message.SendMessageRequestHeader;
import com.webank.eventmesh.common.protocol.http.header.message.SendMessageResponseHeader;
import com.webank.eventmesh.runtime.util.ProxyUtil;
import com.webank.eventmesh.runtime.util.RemotingHelper;
import io.netty.channel.ChannelHandlerContext;
import io.openmessaging.api.Message;
import io.openmessaging.api.OnExceptionContext;
import io.openmessaging.api.SendCallback;
import io.openmessaging.api.SendResult;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SendAsyncMessageProcessor implements HttpRequestProcessor {

    public Logger messageLogger = LoggerFactory.getLogger("message");

    public Logger httpLogger = LoggerFactory.getLogger("http");

    public Logger cmdLogger = LoggerFactory.getLogger("cmd");

    private ProxyHTTPServer proxyHTTPServer;

    public SendAsyncMessageProcessor(ProxyHTTPServer proxyHTTPServer) {
        this.proxyHTTPServer = proxyHTTPServer;
    }

    @Override
    public void processRequest(ChannelHandlerContext ctx, AsyncContext<HttpCommand> asyncContext) throws Exception {

        HttpCommand responseProxyCommand;

        cmdLogger.info("cmd={}|{}|client2proxy|from={}|to={}", RequestCode.get(Integer.valueOf(asyncContext.getRequest().getRequestCode())),
                ProxyConstants.PROTOCOL_HTTP,
                RemotingHelper.parseChannelRemoteAddr(ctx.channel()), IPUtil.getLocalAddress());

        SendMessageRequestHeader sendMessageRequestHeader = (SendMessageRequestHeader) asyncContext.getRequest().getHeader();
        SendMessageRequestBody sendMessageRequestBody = (SendMessageRequestBody) asyncContext.getRequest().getBody();

        SendMessageResponseHeader sendMessageResponseHeader =
                SendMessageResponseHeader.buildHeader(Integer.valueOf(asyncContext.getRequest().getRequestCode()), proxyHTTPServer.getProxyConfiguration().proxyCluster,
                        IPUtil.getLocalAddress(), proxyHTTPServer.getProxyConfiguration().proxyEnv,
                        proxyHTTPServer.getProxyConfiguration().proxyRegion,
                        proxyHTTPServer.getProxyConfiguration().proxyDCN, proxyHTTPServer.getProxyConfiguration().proxyIDC);

        //validate header
        if (StringUtils.isBlank(sendMessageRequestHeader.getIdc())
                || StringUtils.isBlank(sendMessageRequestHeader.getDcn())
                || StringUtils.isBlank(sendMessageRequestHeader.getPid())
                || !StringUtils.isNumeric(sendMessageRequestHeader.getPid())
                || StringUtils.isBlank(sendMessageRequestHeader.getSys())) {
            responseProxyCommand = asyncContext.getRequest().createHttpCommandResponse(
                    sendMessageResponseHeader,
                    SendMessageResponseBody.buildBody(ProxyRetCode.PROXY_PROTOCOL_HEADER_ERR.getRetCode(), ProxyRetCode.PROXY_PROTOCOL_HEADER_ERR.getErrMsg()));
            asyncContext.onComplete(responseProxyCommand);
            return;
        }

        //validate body
        if (StringUtils.isBlank(sendMessageRequestBody.getBizSeqNo())
                || StringUtils.isBlank(sendMessageRequestBody.getUniqueId())
                || StringUtils.isBlank(sendMessageRequestBody.getTopic())
                || StringUtils.isBlank(sendMessageRequestBody.getContent())
                || (StringUtils.isBlank(sendMessageRequestBody.getTtl()))) {
            //sync message TTL can't be empty
            responseProxyCommand = asyncContext.getRequest().createHttpCommandResponse(
                    sendMessageResponseHeader,
                    SendMessageResponseBody.buildBody(ProxyRetCode.PROXY_PROTOCOL_BODY_ERR.getRetCode(), ProxyRetCode.PROXY_PROTOCOL_BODY_ERR.getErrMsg()));
            asyncContext.onComplete(responseProxyCommand);
            return;
        }

        String producerGroup = ProxyUtil.buildClientGroup(sendMessageRequestHeader.getSys(),
                sendMessageRequestHeader.getDcn());
        ProxyProducer proxyProducer = proxyHTTPServer.getProducerManager().getProxyProducer(producerGroup);

        if (!proxyProducer.getStarted().get()) {
            responseProxyCommand = asyncContext.getRequest().createHttpCommandResponse(
                    sendMessageResponseHeader,
                    SendMessageResponseBody.buildBody(ProxyRetCode.PROXY_GROUP_PRODUCER_STOPED_ERR.getRetCode(), ProxyRetCode.PROXY_GROUP_PRODUCER_STOPED_ERR.getErrMsg()));
            asyncContext.onComplete(responseProxyCommand);
            return;
        }

        String ttl = String.valueOf(ProxyConstants.DEFAULT_MSG_TTL_MILLS);
        if (StringUtils.isNotBlank(sendMessageRequestBody.getTtl()) && StringUtils.isNumeric(sendMessageRequestBody.getTtl())) {
            ttl = sendMessageRequestBody.getTtl();
        }

        Message omsMsg = new Message();
        try {
            // body
            omsMsg.setBody(sendMessageRequestBody.getContent().getBytes(ProxyConstants.DEFAULT_CHARSET));
            // topic
            omsMsg.setTopic(sendMessageRequestBody.getTopic());
            omsMsg.putSystemProperties(Constants.PROPERTY_MESSAGE_DESTINATION, sendMessageRequestBody.getTopic());

            if (!StringUtils.isBlank(sendMessageRequestBody.getTag())) {
                omsMsg.putUserProperties(ProxyConstants.TAG, sendMessageRequestBody.getTag());
            }
            // ttl
            omsMsg.putUserProperties(Constants.PROPERTY_MESSAGE_TIMEOUT, ttl);
            // bizNo
            omsMsg.putSystemProperties(Constants.PROPERTY_MESSAGE_SEARCH_KEYS, sendMessageRequestBody.getBizSeqNo());
            omsMsg.putUserProperties("msgType", "persistent");
            omsMsg.putUserProperties(ProxyConstants.REQ_C2PROXY_TIMESTAMP, String.valueOf(System.currentTimeMillis()));
            omsMsg.putUserProperties(Constants.RMB_UNIQ_ID, sendMessageRequestBody.getUniqueId());
            omsMsg.putUserProperties(ProxyConstants.REQ_PROXY2MQ_TIMESTAMP, String.valueOf(System.currentTimeMillis()));

            // new rocketmq client can't support put DeFiBusConstant.PROPERTY_MESSAGE_TTL
//            rocketMQMsg.putUserProperty(DeFiBusConstant.PROPERTY_MESSAGE_TTL, ttl);

            if (messageLogger.isDebugEnabled()) {
                messageLogger.debug("msg2MQMsg suc, bizSeqNo={}, topic={}", sendMessageRequestBody.getBizSeqNo(),
                        sendMessageRequestBody.getTopic());
            }
        } catch (Exception e) {
            messageLogger.error("msg2MQMsg err, bizSeqNo={}, topic={}", sendMessageRequestBody.getBizSeqNo(),
                    sendMessageRequestBody.getTopic(), e);
            responseProxyCommand = asyncContext.getRequest().createHttpCommandResponse(
                    sendMessageResponseHeader,
                    SendMessageResponseBody.buildBody(ProxyRetCode.PROXY_PACKAGE_MSG_ERR.getRetCode(), ProxyRetCode.PROXY_PACKAGE_MSG_ERR.getErrMsg() + ProxyUtil.stackTrace(e, 2)));
            asyncContext.onComplete(responseProxyCommand);
            return;
        }

        final SendMessageContext sendMessageContext = new SendMessageContext(sendMessageRequestBody.getBizSeqNo(), omsMsg, proxyProducer, proxyHTTPServer);
        proxyHTTPServer.metrics.summaryMetrics.recordSendMsg();

        long startTime = System.currentTimeMillis();

        final CompleteHandler<HttpCommand> handler = new CompleteHandler<HttpCommand>() {
            @Override
            public void onResponse(HttpCommand httpCommand) {
                try {
                    if (httpLogger.isDebugEnabled()) {
                        httpLogger.debug("{}", httpCommand);
                    }
                    proxyHTTPServer.sendResponse(ctx, httpCommand.httpResponse());
                    proxyHTTPServer.metrics.summaryMetrics.recordHTTPReqResTimeCost(System.currentTimeMillis() - asyncContext.getRequest().getReqTime());
                } catch (Exception ex) {
                }
            }
        };

        LiteMessage liteMessage = new LiteMessage(sendMessageRequestBody.getBizSeqNo(),
                sendMessageRequestBody.getUniqueId(), sendMessageRequestBody.getTopic(),
                sendMessageRequestBody.getContent())
                .setProp(sendMessageRequestBody.getExtFields());

        try {
            sendMessageContext.getMsg().getUserProperties().put(ProxyConstants.REQ_PROXY2MQ_TIMESTAMP, String.valueOf(System.currentTimeMillis()));
            proxyProducer.send(sendMessageContext, new SendCallback() {

                @Override
                public void onSuccess(SendResult sendResult) {
                    HttpCommand succ = asyncContext.getRequest().createHttpCommandResponse(
                            sendMessageResponseHeader,
                            SendMessageResponseBody.buildBody(ProxyRetCode.SUCCESS.getRetCode(), ProxyRetCode.SUCCESS.getErrMsg() + sendResult.toString()));
                    asyncContext.onComplete(succ, handler);
                    long endTime = System.currentTimeMillis();
                    proxyHTTPServer.metrics.summaryMetrics.recordSendMsgCost(endTime - startTime);
                    messageLogger.info("message|proxy2mq|REQ|ASYNC|send2MQCost={}ms|topic={}|bizSeqNo={}|uniqueId={}",
                            endTime - startTime,
                            sendMessageRequestBody.getTopic(),
                            sendMessageRequestBody.getBizSeqNo(),
                            sendMessageRequestBody.getUniqueId());
                }

                @Override
                public void onException(OnExceptionContext context) {
                    HttpCommand err = asyncContext.getRequest().createHttpCommandResponse(
                            sendMessageResponseHeader,
                            SendMessageResponseBody.buildBody(ProxyRetCode.PROXY_SEND_ASYNC_MSG_ERR.getRetCode(),
                                    ProxyRetCode.PROXY_SEND_ASYNC_MSG_ERR.getErrMsg() + ProxyUtil.stackTrace(context.getException(), 2)));
                    asyncContext.onComplete(err, handler);
                    long endTime = System.currentTimeMillis();
                    proxyHTTPServer.metrics.summaryMetrics.recordSendMsgFailed();
                    proxyHTTPServer.metrics.summaryMetrics.recordSendMsgCost(endTime - startTime);
                    messageLogger.error("message|proxy2mq|REQ|ASYNC|send2MQCost={}ms|topic={}|bizSeqNo={}|uniqueId={}",
                            endTime - startTime,
                            sendMessageRequestBody.getTopic(),
                            sendMessageRequestBody.getBizSeqNo(),
                            sendMessageRequestBody.getUniqueId(), context.getException());
                }
            });
        } catch (Exception ex) {
            HttpCommand err = asyncContext.getRequest().createHttpCommandResponse(
                    sendMessageResponseHeader,
                    SendMessageResponseBody.buildBody(ProxyRetCode.PROXY_SEND_ASYNC_MSG_ERR.getRetCode(),
                            ProxyRetCode.PROXY_SEND_ASYNC_MSG_ERR.getErrMsg() + ProxyUtil.stackTrace(ex, 2)));
            asyncContext.onComplete(err);
            long endTime = System.currentTimeMillis();
            messageLogger.error("message|proxy2mq|REQ|ASYNC|send2MQCost={}ms|topic={}|bizSeqNo={}|uniqueId={}",
                    endTime - startTime,
                    sendMessageRequestBody.getTopic(),
                    sendMessageRequestBody.getBizSeqNo(),
                    sendMessageRequestBody.getUniqueId(), ex);
            proxyHTTPServer.metrics.summaryMetrics.recordSendMsgFailed();
            proxyHTTPServer.metrics.summaryMetrics.recordSendMsgCost(endTime - startTime);
        }

        return;
    }

    @Override
    public boolean rejectRequest() {
        return false;
    }

}
