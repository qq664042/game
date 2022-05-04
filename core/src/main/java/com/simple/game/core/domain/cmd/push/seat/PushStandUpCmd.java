package com.simple.game.core.domain.cmd.push.seat;

import com.simple.game.core.domain.cmd.push.PushCmd;
import com.simple.game.core.domain.cmd.req.seat.ReqStandUpCmd;

import lombok.Data;

@Data
public class PushStandUpCmd extends PushSeatCmd{
	private long playerId;
	private String nickname;
	private String headPic;
	
	@Override
	public int getCode() {
		return ReqStandUpCmd.CODE + PushCmd.PUSH_NUM;
	}

	@Override
	public String toLogStr() {
		// TODO Auto-generated method stub
		return null;
	}


}
