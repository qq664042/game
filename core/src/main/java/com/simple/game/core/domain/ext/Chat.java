package com.simple.game.core.domain.ext;

import com.simple.game.core.domain.dto.constant.ChatKind;

import lombok.Data;
import lombok.ToString;

@Data
@ToString
public class Chat {
	private ChatKind kind;
	
	private String content; 
	
	
}
