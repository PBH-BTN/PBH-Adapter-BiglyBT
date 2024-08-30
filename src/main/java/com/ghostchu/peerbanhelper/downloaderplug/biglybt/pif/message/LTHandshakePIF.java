package com.ghostchu.peerbanhelper.downloaderplug.biglybt.pif.message;

import com.biglybt.core.peermanager.messaging.bittorrent.BTPiece;
import com.biglybt.core.peermanager.messaging.bittorrent.ltep.LTHandshake;
import com.biglybt.pif.messaging.Message;
import com.biglybt.pifimpl.local.messaging.MessageAdapter;

public class LTHandshakePIF extends MessageAdapter {
    public LTHandshakePIF() {
        super(new LTHandshake());
    }
    protected BTMessagePiece( com.biglybt.core.peermanager.messaging.Message core_msg ) {
        super( core_msg );
        piece = (BTPiece)core_msg;
    }

}
