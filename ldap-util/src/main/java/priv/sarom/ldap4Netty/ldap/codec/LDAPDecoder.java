package priv.sarom.ldap4Netty.ldap.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.directory.api.asn1.DecoderException;
import org.apache.directory.api.ldap.model.message.Message;

import java.io.ByteArrayInputStream;
import java.util.List;

/**
 * 说明:
 *
 * @author: cxy, 2018/11/2
 */
@Slf4j
public class LDAPDecoder extends ByteToMessageDecoder {

    private byte[] remainData;

    @Override
    protected void decode(ChannelHandlerContext channelHandlerContext, ByteBuf byteBuf, List<Object> list) throws Exception {

        //通过比较LDAPDecoder start time 的次数 和 Modify Operation 的次数判断是否粘包（单次截断tcp流过长）
        // loop 50
        // start time 36
        // enter LDAPBindHandler 50
        // enter LDAPModifyHandler 50
        log.info("start time:" + System.nanoTime());

        // 可能出现半包
        ByteBuf mergeByteBuf = null;
        if (remainData != null && remainData.length > 0) {
            ByteBuf remain = Unpooled.copiedBuffer(remainData);
            mergeByteBuf = Unpooled.compositeBuffer().addComponent(remain).addComponent(byteBuf);
        } else {
            mergeByteBuf = byteBuf;
        }
        ByteBuf byteBuf1 = Unpooled.buffer(mergeByteBuf.readableBytes()).writeBytes(byteBuf);
        byte[] mergeData = mergeByteBuf.hasArray() ? mergeByteBuf.array()
                : byteBuf1.array();
        ReferenceCountUtil.release(byteBuf1);

        Message msg = null;
        int processedLength = 0;
        for (int matchLength = 1; matchLength <= mergeData.length; matchLength++) {
            try {
                msg = MyLDAPDecoder.decode2Message(new ByteArrayInputStream(mergeData, processedLength, matchLength - processedLength));
                if (msg != null) {
                    processedLength = matchLength;
                    list.add(msg);
                    log.info("unpack success");
                }
            } catch (DecoderException e) {
                if (e.getMessage().contains("ERR_01200_BAD_TRANSITION_FROM_STATE")) {
                    //丢弃脏数据
                    processedLength = matchLength;
                } else if (e.getMessage().contains("ERR_05205_PDU_DOES_NOT_CONTAIN_ENOUGH_DATA")) {
                    //数据不足以解析，等待后续数据
                    continue;
                }
                continue;
            }
        }

        // 可能存在半包
        int newlen = mergeData.length - processedLength;
        if (newlen > 0) {
            remainData = new byte[newlen];
            System.arraycopy(mergeData, processedLength, remainData, 0, newlen);
        } else {
            remainData = null;
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        cause.printStackTrace();
        ctx.close();
    }
}
