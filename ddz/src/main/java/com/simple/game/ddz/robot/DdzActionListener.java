package com.simple.game.ddz.robot;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.simple.game.core.constant.GameConstant;
import com.simple.game.core.domain.dto.GameSeat;
import com.simple.game.core.domain.dto.GameSessionInfo;
import com.simple.game.core.domain.dto.constant.SCard;
import com.simple.game.core.robot.ActionListener;
import com.simple.game.core.util.MyThreadFactory;
import com.simple.game.ddz.domain.cmd.push.game.notify.NotifyDoubledCmd;
import com.simple.game.ddz.domain.cmd.push.game.notify.NotifyGameOverCmd;
import com.simple.game.ddz.domain.cmd.push.game.notify.NotifyGameSkipCmd;
import com.simple.game.ddz.domain.cmd.push.game.notify.NotifySendCardCmd;
import com.simple.game.ddz.domain.cmd.push.seat.PushPlayCardCmd;
import com.simple.game.ddz.domain.cmd.push.seat.PushRobLandlordCmd;
import com.simple.game.ddz.domain.cmd.req.seat.ReqDoubledCmd;
import com.simple.game.ddz.domain.cmd.req.seat.ReqDoubledShowCardCmd;
import com.simple.game.ddz.domain.cmd.req.seat.ReqPlayCardCmd;
import com.simple.game.ddz.domain.cmd.req.seat.ReqReadyNextCmd;
import com.simple.game.ddz.domain.cmd.req.seat.ReqRobLandlordCmd;
import com.simple.game.ddz.domain.cmd.rtn.seat.RtnPlayCardCmd;
import com.simple.game.ddz.domain.cmd.rtn.seat.RtnRobLandlordCmd;
import com.simple.game.ddz.domain.dto.DdzGameSeat;
import com.simple.game.ddz.domain.dto.config.DdzDeskItem;
import com.simple.game.ddz.domain.dto.config.DdzGameItem;

public class DdzActionListener extends ActionListener{
	private final static Logger logger = LoggerFactory.getLogger(DdzActionListener.class);
	private final static DelayQueue<DelayedItem<CmdTask>> queue = new DelayQueue<DelayedItem<CmdTask>>();
	protected final static ThreadPoolExecutor delayPool = new ThreadPoolExecutor(1, coreSize,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(150000),
            new MyThreadFactory("DdzDelayAction"));
	
	static {
		delayPool.execute(new Consumer(queue));
		logger.info("启动斗地主机器人延时操作");
	}
	
	@Override
	protected void onReceived(CmdTask cmdTask) {
		GameSessionInfo gameSessionInfo = (GameSessionInfo)cmdTask.getGameSession().getAttachment().get(GameConstant.GAME_SESSION_INFO);
		GameSeat deskSeat = (GameSeat)gameSessionInfo.getAddress();
		DdzGameItem gameItem = (DdzGameItem)deskSeat.getDesk().getGameItem();
		DdzDeskItem deskItem = (DdzDeskItem)deskSeat.getDesk().getDeskItem();
		RobotPlayer robotPlayer = (RobotPlayer)cmdTask.getGameSession().getAttachment().get(RobotPlayer.ROBOT_CACHE);
		
		logger.info("-----接收到：" + cmdTask.getCmd());
		
		Random random = new Random();
		if(cmdTask.getCmd() instanceof NotifySendCardCmd) {
			//先完牌就准备抢地主
			NotifySendCardCmd notifyCmd = (NotifySendCardCmd)cmdTask.getCmd();
			robotPlayer.setCards(new ArrayList<Integer>(notifyCmd.getCards()));
			int delaySecond = 3 + random.nextInt(gameItem.getMaxRobbedLandlordSecond()/2);
			queue.offer(new DelayedItem<CmdTask>(delaySecond, cmdTask));
		}
		else if(cmdTask.getCmd() instanceof NotifyGameSkipCmd) {
			//跳过了，就准备下一局
			int delaySecond = 2 + random.nextInt(10);
			queue.offer(new DelayedItem<CmdTask>(delaySecond, cmdTask));
		} 
		else if(cmdTask.getCmd() instanceof RtnRobLandlordCmd) {
			//如果抢到地主位了
			RtnRobLandlordCmd rtnCmd = (RtnRobLandlordCmd)cmdTask.getCmd();
			if(rtnCmd.getCode() == 0) {
				robotPlayer.addCommonCards(rtnCmd.getCards());
				robotPlayer.beenLandlord();
				//加倍的概率3/5
				if(random.nextInt(5) > 1) {
					int delaySecond = 1 + random.nextInt(gameItem.getMaxDoubleSecond()/3);
					queue.offer(new DelayedItem<CmdTask>(delaySecond, cmdTask));
				}
			}
		} 
		else if(cmdTask.getCmd() instanceof PushRobLandlordCmd) {
			//设置地主位
			PushRobLandlordCmd pushCmd = (PushRobLandlordCmd)cmdTask.getCmd();
			robotPlayer.setLandlordPosition(pushCmd.getPosition());
			//加倍的概率1/2
			if(random.nextInt(2) == 1) {
				int delaySecond = 1 + random.nextInt(gameItem.getMaxDoubleSecond()/3);
				queue.offer(new DelayedItem<CmdTask>(delaySecond, cmdTask));
			}
		} 
		else if(cmdTask.getCmd() instanceof NotifyDoubledCmd) {
			//如果抢到地主位了，就得准备出牌
			if(robotPlayer.isLandlord()) {
				int delaySecond = 1 + random.nextInt(gameItem.getMaxFirstPlayCardSecond()/3);
				queue.offer(new DelayedItem<CmdTask>(delaySecond, cmdTask));
				logger.info("准备出牌：" + delaySecond);
			}
		} 
		else if(cmdTask.getCmd() instanceof RtnPlayCardCmd) {
			//如果出牌成功
			RtnPlayCardCmd rtnCmd = (RtnPlayCardCmd)cmdTask.getCmd();
			if(rtnCmd.getCode() == 0) {
				robotPlayer.removeCards(rtnCmd.getResidueCount());
			}
		} 
		else if(cmdTask.getCmd() instanceof PushPlayCardCmd) {
			//判断是不是到自己出牌
			PushPlayCardCmd pushCmd = (PushPlayCardCmd)cmdTask.getCmd();
			int nextPosition = pushCmd.getPosition()+1;
			if(nextPosition > deskItem.getMaxPosition()) {
				nextPosition = deskItem.getMinPosition();
			}
			if(nextPosition == deskSeat.getPosition()) {
				//判断是否是王牌
				boolean isKingBombs = false;
				if(pushCmd.getCards() != null && pushCmd.getCards().size() == 2) {
					if(pushCmd.getCards().contains(SCard.STRONG_KING.getFace()) && pushCmd.getCards().contains(SCard.WEAK_KING.getFace())) {
						isKingBombs = true;
					}
				}
				
				int delaySecond = 2;
				if(!isKingBombs) {
					delaySecond += random.nextInt(gameItem.getMaxPlayCardSecond()/3);
				}
				queue.offer(new DelayedItem<CmdTask>(delaySecond, cmdTask));
			}
			//记牌
			robotPlayer.addOutCards(pushCmd.getPosition(), pushCmd.getCards(), pushCmd.getResidueCount());
		} 
		else if(cmdTask.getCmd() instanceof NotifyGameOverCmd) {
			//游戏结束就准备下一局
			int delaySecond = 2 + random.nextInt(10);
			queue.offer(new DelayedItem<CmdTask>(delaySecond, cmdTask));
		} 
	}
	
	 
	static class DelayedItem<T> implements Delayed {
		/**
		 * 到期时间，单位ms
		 */
		private long deathTime;
		private T data;
	 
		public DelayedItem(int delaySecond, T data) {
			this.deathTime = System.currentTimeMillis() + delaySecond * 1000;
			this.data = data;
		}
	 
		public long getDeathTime() {
			return deathTime;
		}
		public T getData() {
			return data;
		}
	 
		@Override
		public int compareTo(Delayed o) {
			long t1 = getDelay(TimeUnit.MILLISECONDS);
			long t2 = o.getDelay(TimeUnit.MILLISECONDS);
			return (int)(t1 - t2);
		}
	 
		@Override
		public long getDelay(TimeUnit unit) {
			long diff = unit.convert(deathTime - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
			return diff;
		}
	}
	
	static class Consumer implements Runnable {
		private DelayQueue<DelayedItem<CmdTask>> queue;
		public Consumer(DelayQueue<DelayedItem<CmdTask>> queue) {
			this.queue = queue;
		}
		@Override
		public void run() {
			while (true) {
				try {
					DelayedItem<CmdTask> item = queue.take();
					CmdTask cmdTask = item.getData();
					onDelay(cmdTask);
				} catch (Exception e) {
					logger.warn("操控机器人失败", e);
				}
			}
		}
	}
	
	
	protected static void onDelay(CmdTask cmdTask) {
		GameSessionInfo gameSessionInfo = (GameSessionInfo)cmdTask.getGameSession().getAttachment().get(GameConstant.GAME_SESSION_INFO);
		DdzGameSeat deskSeat = (DdzGameSeat)gameSessionInfo.getAddress();
		RobotPlayer robotPlayer = (RobotPlayer)cmdTask.getGameSession().getAttachment().get(RobotPlayer.ROBOT_CACHE);
		
		if(cmdTask.getCmd() instanceof NotifySendCardCmd) {
			//发完牌就准备抢地主
    		ReqRobLandlordCmd reqCmd = new ReqRobLandlordCmd();
    		reqCmd.setScore(3);
    		deskSeat.robLandlord(gameSessionInfo, reqCmd);
		}
		else if(cmdTask.getCmd() instanceof NotifyGameSkipCmd) {
			//跳过了，就准备下一局
			ReqReadyNextCmd reqCmd = new ReqReadyNextCmd();
			deskSeat.readyNext(gameSessionInfo, reqCmd);
			
			//通用返回值捕捉不到，直接当成功处理
			robotPlayer.clear();
		} 
		else if(cmdTask.getCmd() instanceof RtnRobLandlordCmd || cmdTask.getCmd() instanceof PushRobLandlordCmd) {
			//如果不幸运，必须得加倍
			Random random = new Random();
			if(random.nextInt(3) == 1) {
				//加倍&明牌
				ReqDoubledShowCardCmd reqCmd = new ReqDoubledShowCardCmd();
				deskSeat.doubledShowCard(gameSessionInfo, reqCmd);
			}
			else {
				//加倍
				ReqDoubledCmd reqCmd = new ReqDoubledCmd();
				deskSeat.doubled(gameSessionInfo, reqCmd);
			}
		}
		else if(cmdTask.getCmd() instanceof NotifyDoubledCmd) {
			//如果抢到地主位了，就得准备出牌
			List<Integer> cards = robotPlayer.sendCard(true);
			ReqPlayCardCmd reqCmd = new ReqPlayCardCmd();
			reqCmd.setCards(cards);
			deskSeat.playCard(gameSessionInfo, reqCmd);
		} 
		else if(cmdTask.getCmd() instanceof PushPlayCardCmd) {
			//到自己出牌
			//需要判断是否结束了
			if(!robotPlayer.isGameOver()) {
				List<Integer> cards = robotPlayer.sendCard(false);
				ReqPlayCardCmd reqCmd = new ReqPlayCardCmd();
				reqCmd.setCards(cards);
				deskSeat.playCard(gameSessionInfo, reqCmd);
			}
		} 
		else if(cmdTask.getCmd() instanceof NotifyGameOverCmd) {
			//游戏结束就准备下一局
			ReqReadyNextCmd reqCmd = new ReqReadyNextCmd();
			deskSeat.readyNext(gameSessionInfo, reqCmd);

			//通用返回值捕捉不到，直接当成功处理
			robotPlayer.clear();
		} 
	}
	
}
