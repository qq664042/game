package com.simple.game.core.domain.cmd.push.game;

import lombok.Data;

@Data
public class PushApplyManagerCmd extends PushGameCmd{
	private long playerId;
	private String nickname;
	
	@Override
	public int getCode() {
		return 1101011;
	}

	@Override
	public String toLogStr() {
		// TODO Auto-generated method stub
		return null;
	}


}