package com.simple.game.core.domain.good.ddz.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import lombok.Data;

/***
 * 
 * 记录结果的变化
 * 
 * @author zhibozhang
 *
 */
@Data
public class ResultManager {
	private static Logger logger = LoggerFactory.getLogger(ResultManager.class);
	
	public static void save(GameResultRecord record) {
		logger.info("游戏结束:{} ", record);
	}
	
}
