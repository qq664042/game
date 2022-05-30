package com.simple.game.ddz.domain.dto;


import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.simple.game.core.domain.cmd.rtn.game.RtnGameInfoCmd;
import com.simple.game.core.domain.cmd.rtn.seat.RtnGameSeatInfoCmd;
import com.simple.game.core.domain.dto.GameSeat;
import com.simple.game.core.domain.dto.Player;
import com.simple.game.core.domain.dto.TableDesk;
import com.simple.game.core.domain.dto.constant.PokerKind;
import com.simple.game.core.domain.dto.constant.SCard;
import com.simple.game.core.domain.manager.CoinManager;
import com.simple.game.core.exception.BizException;
import com.simple.game.ddz.domain.cmd.push.game.notify.NotifyGameOverCmd;
import com.simple.game.ddz.domain.cmd.push.game.notify.NotifyGameSkipCmd;
import com.simple.game.ddz.domain.cmd.push.game.notify.NotifySendCardCmd;
import com.simple.game.ddz.domain.cmd.push.seat.PushPlayCardCmd;
import com.simple.game.ddz.domain.cmd.rtn.game.RtnDdzGameInfoCmd;
import com.simple.game.ddz.domain.cmd.rtn.game.RtnDdzGameInfoCmd.OutCard;
import com.simple.game.ddz.domain.cmd.rtn.seat.RtnDdzGameSeatCmd;
import com.simple.game.ddz.domain.dto.config.DdzDeskItem;
import com.simple.game.ddz.domain.dto.config.DdzGameItem;
import com.simple.game.ddz.domain.dto.constant.ddz.DoubleKind;
import com.simple.game.ddz.domain.dto.constant.ddz.GameProgress;
import com.simple.game.ddz.domain.manager.GameResultRecord;
import com.simple.game.ddz.domain.manager.GameResultRecord.ResultItem;
import com.simple.game.ddz.domain.manager.ResultManager;
import com.simple.game.ddz.domain.ruler.DdzCard;
import com.simple.game.ddz.domain.ruler.DdzRuler;

import lombok.Getter;
import lombok.ToString;

/***
 * 斗地主的游戏桌
 * 
 * @author zhibozhang
 *
 */
@ToString
@Getter
public class DdzDesk extends TableDesk{
	private static Logger logger = LoggerFactory.getLogger(DdzDesk.class);
	
	protected final DdzCard ddzCard = new DdzCard();
	
	public DdzDesk(DdzGameItem gameItem, DdzDeskItem deskItem) {
		super(gameItem, deskItem);
	} 

	/***是否在进行中****/
	protected GameProgress currentProgress = GameProgress.ready;
	private boolean settling = false;
	
	
	/***投降位***/
	protected int surrenderPosition;
	
	/***最近的游戏结束时间***/
	protected long lastGameOverTime;
	/***最近系统发牌时间***/
	protected long lastSendCardTime;
	
	/***最近一次过牌时间***/
	protected long lastPlayCardTime;
	
	public /* synchronized */ boolean onScan() {
		if(currentProgress == GameProgress.ready) {
			if(this.playerMap.size() == 0) {
				return false;
			}
			
			boolean standuped = false;
			int readyCount = 0;
			handleDisconnectPlayer();
			for(int position = deskItem.getMinPosition(); position <= deskItem.getMaxPosition(); position++) {
				DdzGameSeat gameSeat = (DdzGameSeat)this.seatPlayingMap.get(position);
				if(gameSeat == null) {
					logger.error("系统有重大bug!!! 席位没找到");
					return false;
				}
				
				if(!gameSeat.isReady()) {
					//还没准备好了
					if(gameSeat.getFansCount() > 0) {
						standuped = forceStandUpPosition(gameSeat);
					}
					continue;
				}
				
				if(gameSeat.getMaster().get() == null) {
					return false;
				}
				readyCount++;
			}
			if(standuped) {
				return standuped;
			}
			
			if(readyCount < 3) {
				return false;
			}
			
			//更换主席位
			for(int position = deskItem.getMinPosition(); position <= deskItem.getMaxPosition(); position++) {
				DdzGameSeat gameSeat = (DdzGameSeat)this.seatPlayingMap.get(position);
				gameSeat.handleChangeMaster();
			}
			
			//可以开始了
			//1.洗牌，
			shuffleCards();
			//2.发牌，3.出牌
			sendCards();
			currentProgress = GameProgress.sended;	
			return true;
		}
		else if(currentProgress == GameProgress.sended) {
			//判断等待抢地主是否超时
			return handleWaitRobLandlordTimeout();
		}
		else if(currentProgress == GameProgress.robbedLandlord) {
			//判断等待出牌是否超时
			return handleWaitPlayCardTimeout();
		}
		else if(currentProgress == GameProgress.gameover || currentProgress == GameProgress.surrender ) {
			//结算游戏
			this.settle();
		}
		return false;
	}
	
	
	public RtnGameInfoCmd getGameInfo() {
		RtnGameInfoCmd gameInfo = super.getGameInfo();
		RtnDdzGameInfoCmd rtnCmd = RtnDdzGameInfoCmd.copy(gameInfo);
		rtnCmd.setCurrentProgress(currentProgress);
		if(currentProgress == GameProgress.ready) {
			return rtnCmd;
		}
		
		rtnCmd.setCommonCards(ddzCard.getCommonCardList());
		if(currentProgress == GameProgress.sended) {
			return rtnCmd;
		}
		
		rtnCmd.setLandlordPosition(ddzCard.getLandlordPosition());
		rtnCmd.setDoubleCount(ddzCard.getDoubleCount());
		if(currentProgress == GameProgress.robbedLandlord) {
			return rtnCmd;
		}
		
		rtnCmd.setCurrentPosition(ddzCard.getCurrentPosition());
		rtnCmd.setSurrenderPosition(surrenderPosition);
		
		DdzRuler.SpanCard[] spanArray = ddzCard.getBattlefield().getData();
		if(spanArray != null && spanArray.length > 0) {
			List<OutCard> battlefield = new ArrayList<OutCard>(spanArray.length);
			rtnCmd.setBattlefield(battlefield);
			
			for(DdzRuler.SpanCard spanCard : spanArray) {
				OutCard outCard = new OutCard();
				outCard.setPosition(spanCard.getPosition());
				outCard.setCards(PokerKind.convertFaceList(spanCard.getCards()));
				battlefield.add(outCard);
			}
		}
		rtnCmd.setLandlordPlayCardCount(ddzCard.getLandlordPlayCardCount());
		rtnCmd.setFarmerPlayCardCount(ddzCard.getFarmerPlayCardCount());
		
//		if(currentProgress == GameProgress.gameover) {
//		}
		
		return rtnCmd;
	}
	
	public RtnDdzGameSeatCmd getSeatInfo(RtnGameSeatInfoCmd seatInfo) {
		RtnDdzGameSeatCmd rtnCmd = new RtnDdzGameSeatCmd();
		rtnCmd.copy(seatInfo);
		
		if(seatInfo.getPosition() == 1) {
			rtnCmd.setCards(ddzCard.getFirstCardList());
		}
		if(seatInfo.getPosition() == 2) {
			rtnCmd.setCards(ddzCard.getSecondCardList());
		}
		if(seatInfo.getPosition() == 3) {
			rtnCmd.setCards(ddzCard.getThirdCardList());
		}
		
		DdzGameSeat gameSeat = (DdzGameSeat)this.seatPlayingMap.get(seatInfo.getPosition());
		rtnCmd.setReady(gameSeat.isReady());
		rtnCmd.setSkipCount(gameSeat.getSkipCount());
		rtnCmd.setTimeoutCount(gameSeat.getTimeoutCount());
		return rtnCmd;
	}
	
	/***
	 * 处理掉线的用户
	 */
	private void handleDisconnectPlayer() {
		for(Player player : offlineMap.values()) {
			long time = System.currentTimeMillis() - player.getOnline().getDisconnectTime();
			if(time == 0 || time / 1000 < this.getDdzGameItem().getMaxDisconnectSecond()) {
				continue;
			}
			
			//判断是否是主席位
			boolean isMaster = false;
			for(GameSeat gameSeat : seatPlayingMap.values()) {
				if(gameSeat.getMaster().get() != null && gameSeat.getMaster().get().getPlayer().getId() != player.getId()) {
					isMaster = true;
					break;
				}
			}
			if(isMaster) {
				continue;
			}
			
			//强制下线
			this.left(player.getId());
		}
	}
	
	private boolean handleWaitRobLandlordTimeout() {
		long time = System.currentTimeMillis() - lastSendCardTime; 
		if(time == 0 || time / 1000 < this.getDdzGameItem().getMaxRobbedLandlordSecond()) {
			return false;
		}
		
		//超时，直接进入下一轮
		handleNext();
		
		//发送广播
		NotifyGameSkipCmd notifyCmd = new NotifyGameSkipCmd();
		broadcast(notifyCmd);
		return true;
	}
	
	/***
	 * 处理出牌超时的
	 * @return
	 */
	private boolean handleWaitPlayCardTimeout() {
		//当前出牌位
		long time = System.currentTimeMillis() - lastPlayCardTime; 
		if(time == 0) {
			return false;
		}
		long second = time / 1000; 
		if(second < this.getDdzGameItem().getDisconnectPlayCardSecond()) {
			return false;
		}
		
		int position = this.ddzCard.getCurrentPosition(); 
		//判断主席位是否掉线且助手为0
		DdzGameSeat gameSeat = (DdzGameSeat)this.getGameSeat(position);
		if(!gameSeat.isDiconnectPlayCard()) {
			//是否超时出牌
			if(second < this.getDdzGameItem().getMaxPlayCardSecond()) {
				return false;
			}
			else {
				gameSeat.timeoutCountIncrease();
			}
		}
		else {
			gameSeat.skipCountIncrease();
		}
		
		//自动过牌
		List<SCard> outCards = new ArrayList<SCard>(1); 
		boolean isGameOver = this.ddzCard.autoPlayCard(outCards);
		afterPlayCard(isGameOver);
		
		//广播
		PushPlayCardCmd pushCmd = new PushPlayCardCmd();
		pushCmd.setPosition(position);
		pushCmd.getCards().addAll(PokerKind.convertFaceList(outCards));
		this.broadcast(pushCmd, true);
		return true;
	}
	
	/****
	 * 清理需要强制站起的用户
	 */
	private boolean forceStandUpPosition(DdzGameSeat gameSeat) {
		long time = System.currentTimeMillis() - lastGameOverTime; 
		if(time != 0 && time / 1000 > this.getDdzGameItem().getMaxReadyNextSecond()) {
			//强制踢出这些站着茅坑不拉屎的人
			gameSeat.standupAll();
			logger.info("游戏结束后等待{}毫秒，{}席位一直不进行准备状态，强制站起", time, gameSeat.getPosition());
			return true;
		}
		
		//强制踢出那些长时间掉线的主席位
		if(!gameSeat.getMaster().get().getPlayer().getOnline().isOnline()) {
			//判断掉线是否超时
			time = System.currentTimeMillis() - gameSeat.getMaster().get().getPlayer().getOnline().getDisconnectTime(); 
			if(time != 0 && time / 1000 > this.getDdzGameItem().getMaxMasterDisconnectSecond()) {
				gameSeat.standupAll();
				logger.info("游戏结束后等待{}毫秒，{}主席位长时间掉线，强制站起", time, gameSeat.getPosition());
			}
			return true;
		}
		
		//强制踢出那些经常出牌超时的人
		if(gameSeat.getTimeoutCount() > this.getDdzGameItem().getMaxPlayCardOuttimeCount()) {
			gameSeat.standupAll();
			logger.info("游戏结束后等待{}毫秒，{}主席位经常牌超时的，强制站起", time, gameSeat.getPosition());
			return true;
		}
		
		//判断是否是经常跳过出牌
		if(gameSeat.getSkipCount() > this.getDdzGameItem().getMaxSkipCount()) {
			gameSeat.standupAll();
			logger.info("游戏结束后等待{}毫秒，{}主席位经常自动跳过的，强制站起", time, gameSeat.getPosition());
			return true;
		}
		return false;
	}
	
	public boolean canStandUp() {
		return currentProgress == GameProgress.ready;
	}
	private void shuffleCards() {
		this.ddzCard.shuffleCards();
	}
	private void sendCards() {
		this.ddzCard.sendCards();
		//发送到客户端
		{
			GameSeat gameSeat = this.seatPlayingMap.get(1);
			NotifySendCardCmd pushCmd = new NotifySendCardCmd();
			pushCmd.setPosition(1);
			pushCmd.getCards().addAll(this.ddzCard.getFirstCardList());
			gameSeat.broadcast(pushCmd);
		}
		{
			GameSeat gameSeat = this.seatPlayingMap.get(2);
			NotifySendCardCmd pushCmd = new NotifySendCardCmd();
			pushCmd.setPosition(1);
			pushCmd.getCards().addAll(this.ddzCard.getSecondCardList());
			gameSeat.broadcast(pushCmd);
		}
		{
			GameSeat gameSeat = this.seatPlayingMap.get(3);
			NotifySendCardCmd pushCmd = new NotifySendCardCmd();
			pushCmd.setPosition(1);
			pushCmd.getCards().addAll(this.ddzCard.getThirdCardList());
			gameSeat.broadcast(pushCmd);
		}
		lastSendCardTime = System.currentTimeMillis();
	}
	

	protected Player buildGamePlayer(Player player) {
		return new DdzPlayer(player);
	}
	
	/***
	 * 抢地主
	 * @param playerId
	 * @param position
	 * @param score		简化操作，暂时不用
	 */
	public synchronized List<Integer> robLandlord(int position, int score) {
		if(currentProgress != GameProgress.sended) {
			throw new BizException("不是发完牌状态，无法进行抢地主");
		}
		
		List<Integer> commonCards = this.ddzCard.setLandlord(position);
		currentProgress = GameProgress.robbedLandlord;
		lastPlayCardTime = System.currentTimeMillis();
		return commonCards;
	}
	
	
	public synchronized void playCard(int position, List<Integer> cards) {
		if(currentProgress != GameProgress.robbedLandlord) {
			throw new BizException("不是抢完状态，无法进行出牌");
		}
		
		boolean isGameOver = this.ddzCard.playCard(position, cards);
		afterPlayCard(isGameOver);
	}
	
	private void afterPlayCard(boolean isGameOver) {
		if(isGameOver) {
			//进入gameover状态了
			currentProgress = GameProgress.gameover;			
		}
		this.lastPlayCardTime = System.currentTimeMillis();
	}
	
	/***
	 * 投降认输
	 * 直接参考@com.simple.game.ddz.domain.dto.config.DdzGameItem.punishSurrenderDoubleCount处理
	 * @param playerId
	 * @param position
	 */
	public synchronized void surrender(int position) {
		if(currentProgress != GameProgress.robbedLandlord) {
			throw new BizException("不是抢完状态，无法进行投降");
		}
		
		//进入surrender状态了
		currentProgress = GameProgress.surrender;			
		surrenderPosition = position;
	}
	

	protected RtnGameInfoCmd getRtnGameInfoCmd() {
		//TODO 
		return new RtnGameInfoCmd();
	}
	
	
	/***
	 * 游戏结算
	 */
	private boolean settle() {
		if(currentProgress != GameProgress.gameover && currentProgress != GameProgress.surrender) {
			logger.error("游戏出现严重的bug，状态不对就调用了");
			throw new BizException("游戏还未结束");
		}
		if(settling) {
			return false;
		}
		settling = true;
		
		GameResultRecord gameResultRecord = null;
		if(currentProgress == GameProgress.gameover) {
			gameResultRecord = handleNormalResult();
		}
		else if(currentProgress == GameProgress.surrender) {
			gameResultRecord = handleSurrenderResult();
		}
		
		//处理逃跑的人
		handleEscapeResult(gameResultRecord);
		
		//游戏进入下一轮
		handleNext();
		settling = false;
		
		//发送广播
		NotifyGameOverCmd notifyCmd = NotifyGameOverCmd.valueOf(gameResultRecord);
		this.broadcast(notifyCmd);
		return true;
	}
	private void handleNext() {
		currentProgress = GameProgress.ready;
		ddzCard.readyNext();
		
		surrenderPosition = 0;
		lastGameOverTime = System.currentTimeMillis();
		((DdzGameSeat)this.seatPlayingMap.get(1)).setReady(false);
		((DdzGameSeat)this.seatPlayingMap.get(2)).setReady(false);
		((DdzGameSeat)this.seatPlayingMap.get(3)).setReady(false);
	}
	
	private void handleEscapeResult(GameResultRecord record) {
		if(record == null) {
			return;
		}
		
		for(int position = deskItem.getMinPosition(); position <= deskItem.getMinPosition(); position++) {
			DdzGameSeat gameSeat = (DdzGameSeat)this.seatPlayingMap.get(position);
			if(gameSeat.getSkipCount() <= this.getDdzGameItem().getEscape2SkipCount()) {
				continue;
			}
			
			//要处罚了(系统一份，另外两个玩家各一份)
			CoinManager.changeCoin(gameSeat.getMaster().get().getPlayer(), -record.getSingleResult()*3, record.getBatchNo(), "逃跑处罚！");
			
			String reason = String.format("%s号位，逃跑处罚！", position);
			if(position == 1) {
				CoinManager.changeCoin(this.seatPlayingMap.get(2).getMaster().get().getPlayer(), record.getSingleResult(), record.getBatchNo(), reason);
				CoinManager.changeCoin(this.seatPlayingMap.get(3).getMaster().get().getPlayer(), record.getSingleResult(), record.getBatchNo(), reason);
			}
			else if(position == 2) {
				CoinManager.changeCoin(this.seatPlayingMap.get(1).getMaster().get().getPlayer(), record.getSingleResult(), record.getBatchNo(), reason);
				CoinManager.changeCoin(this.seatPlayingMap.get(3).getMaster().get().getPlayer(), record.getSingleResult(), record.getBatchNo(), reason);
			}
			else {
				CoinManager.changeCoin(this.seatPlayingMap.get(1).getMaster().get().getPlayer(), record.getSingleResult(), record.getBatchNo(), reason);
				CoinManager.changeCoin(this.seatPlayingMap.get(2).getMaster().get().getPlayer(), record.getSingleResult(), record.getBatchNo(), reason);
			}
		}
	}
	
	private GameResultRecord handleNormalResult() {
		int doubleCount = this.ddzCard.getDoubleCount();
		if(this.ddzCard.isSpring()) {
			doubleCount += 1;
		}
		long singleResult = this.getDoubleKind().calcResult(this.getDdzDeskItem().getUnitPrice(), doubleCount);
		int landlordPosition = this.ddzCard.getLandlordPosition();
		boolean landlordWin = this.ddzCard.isLandlordWin();
		
		GameResultRecord gameResultRecord = new GameResultRecord();
		gameResultRecord.setUnitPrice(this.getDdzDeskItem().getUnitPrice());
		gameResultRecord.setDoubleCount(doubleCount);
		gameResultRecord.setSingleResult(singleResult);
		gameResultRecord.setDoubleKind(getDoubleKind());
		gameResultRecord.setLandlordPosition(landlordPosition);
		gameResultRecord.getCards().addAll(this.ddzCard.getAllCardList());
		
		if(landlordPosition == 1) {
			{
				Player first = this.seatPlayingMap.get(1).getMaster().get().getPlayer();
				long coin = landlordWin ? (singleResult * 2) : -(singleResult * 2);
				String reason = landlordWin ? "当地主赢了" : "当地主输了";
				CoinManager.changeCoin(first, coin, gameResultRecord.getBatchNo(), reason);
				gameResultRecord.getMap().put(1, new ResultItem(first.getId(), coin));
			}
			{
				Player second = this.seatPlayingMap.get(2).getMaster().get().getPlayer();
				long coin = landlordWin ? (-singleResult) : (singleResult);
				String reason = landlordWin ? "当农民输了" : "当农民赢了";
				CoinManager.changeCoin(second, coin, gameResultRecord.getBatchNo(), reason);
				gameResultRecord.getMap().put(2, new ResultItem(second.getId(), coin));
			}
			{
				Player third = this.seatPlayingMap.get(3).getMaster().get().getPlayer();
				long coin = landlordWin ? -(singleResult) : (singleResult);
				String reason = landlordWin ? "当农民输了" : "当农民赢了";
				CoinManager.changeCoin(third, coin, gameResultRecord.getBatchNo(), reason);
				gameResultRecord.getMap().put(3, new ResultItem(third.getId(), coin));
			}
		}
		else if(landlordPosition == 2) {
			{
				Player second = this.seatPlayingMap.get(2).getMaster().get().getPlayer();
				long coin = landlordWin ? (singleResult * 2) : -(singleResult * 2);
				String reason = landlordWin ? "当地主赢了" : "当地主输了";
				CoinManager.changeCoin(second, coin, gameResultRecord.getBatchNo(), reason);
				gameResultRecord.getMap().put(2, new ResultItem(second.getId(), coin));
			}
			{
				Player first = this.seatPlayingMap.get(1).getMaster().get().getPlayer();
				long coin = landlordWin ? -(singleResult) : (singleResult);
				String reason = landlordWin ? "当农民输了" : "当农民赢了";
				CoinManager.changeCoin(first, coin, gameResultRecord.getBatchNo(), reason);
				gameResultRecord.getMap().put(1, new ResultItem(first.getId(), coin));
			}
			{
				Player third = this.seatPlayingMap.get(3).getMaster().get().getPlayer();
				long coin = landlordWin ? -(singleResult) : (singleResult);
				String reason = landlordWin ? "当农民输了" : "当农民赢了";
				CoinManager.changeCoin(third, coin, gameResultRecord.getBatchNo(), reason);
				gameResultRecord.getMap().put(3, new ResultItem(third.getId(), coin));
			}
		}
		else{
			{
				Player third = this.seatPlayingMap.get(3).getMaster().get().getPlayer();
				long coin = landlordWin ? (singleResult * 2) : -(singleResult * 2);
				String reason = landlordWin ? "当地主赢了" : "当地主输了";
				CoinManager.changeCoin(third, coin, gameResultRecord.getBatchNo(), reason);
				gameResultRecord.getMap().put(3, new ResultItem(third.getId(), coin));
			}
			{
				Player first = this.seatPlayingMap.get(1).getMaster().get().getPlayer();
				long coin = landlordWin ? -(singleResult) : (singleResult);
				String reason = landlordWin ? "当农民输了" : "当农民赢了";
				CoinManager.changeCoin(first, coin, gameResultRecord.getBatchNo(), reason);
				gameResultRecord.getMap().put(1, new ResultItem(first.getId(), coin));
			}
			{
				Player second = this.seatPlayingMap.get(2).getMaster().get().getPlayer();
				long coin = landlordWin ? -(singleResult) : (singleResult);
				String reason = landlordWin ? "当农民输了" : "当农民赢了";
				CoinManager.changeCoin(second, coin, gameResultRecord.getBatchNo(), reason);
				gameResultRecord.getMap().put(2, new ResultItem(second.getId(), coin));
			}
		}
		ResultManager.save(gameResultRecord);
		
		return gameResultRecord;
	}
	
	/***
	 * 投降者赔偿当前的损失，并且加倍偿还
	 * @return
	 */
	private GameResultRecord handleSurrenderResult() {
		int doubleCount = this.ddzCard.getDoubleCount() + this.getDdzGameItem().getPunishSurrenderDoubleCount();
		if(this.ddzCard.isSpring()) {
			doubleCount += 1;
		}
		long singleResult = this.getDoubleKind().calcResult(this.getDdzDeskItem().getUnitPrice(), doubleCount);
		int landlordPosition = this.ddzCard.getLandlordPosition();
		boolean landlordWin = (landlordPosition != this.surrenderPosition);
		
		GameResultRecord gameResultRecord = new GameResultRecord();
		gameResultRecord.setUnitPrice(this.getDdzDeskItem().getUnitPrice());
		gameResultRecord.setDoubleCount(doubleCount);
		gameResultRecord.setDoubleKind(getDoubleKind());
		gameResultRecord.setLandlordPosition(landlordPosition);
		gameResultRecord.getCards().addAll(this.ddzCard.getAllCardList());
		
		if(landlordWin) {
			if(landlordPosition == 1) {
				{
					Player first = this.seatPlayingMap.get(1).getMaster().get().getPlayer();
					long coin = (singleResult * 2);
					CoinManager.changeCoin(first, coin, gameResultRecord.getBatchNo(), "当地主赢了,对方有投降");
					gameResultRecord.getMap().put(1, new ResultItem(first.getId(), coin));
				}
				{
					Player second = this.seatPlayingMap.get(2).getMaster().get().getPlayer();
					if(surrenderPosition == 2) {
						long coin = -(singleResult * 2);
						CoinManager.changeCoin(second, coin, gameResultRecord.getBatchNo(), "当农民输了,我投降了");
						gameResultRecord.getMap().put(2, new ResultItem(second.getId(), coin));
					}
				}
				{
					Player third = this.seatPlayingMap.get(3).getMaster().get().getPlayer();
					if(surrenderPosition == 3) {
						long coin = -(singleResult * 2);
						CoinManager.changeCoin(third, coin, gameResultRecord.getBatchNo(), "当农民输了,我投降了");
						gameResultRecord.getMap().put(3, new ResultItem(third.getId(), coin));
					}
				}
			}
			else if(landlordPosition == 2) {
				{
					Player second = this.seatPlayingMap.get(2).getMaster().get().getPlayer();
					long coin = (singleResult * 2);
					CoinManager.changeCoin(second, coin, gameResultRecord.getBatchNo(), "当地主赢了,对方有投降");
					gameResultRecord.getMap().put(2, new ResultItem(second.getId(), coin));
				}
				{
					Player first = this.seatPlayingMap.get(1).getMaster().get().getPlayer();
					if(surrenderPosition == 1) {
						long coin = -(singleResult * 2);
						CoinManager.changeCoin(first, coin, gameResultRecord.getBatchNo(), "当农民输了,我投降了");
						gameResultRecord.getMap().put(1, new ResultItem(first.getId(), coin));
					}
				}
				{
					Player third = this.seatPlayingMap.get(3).getMaster().get().getPlayer();
					if(surrenderPosition == 3) {
						long coin = -(singleResult * 2);
						CoinManager.changeCoin(third, coin, gameResultRecord.getBatchNo(), "当农民输了,我投降了");
						gameResultRecord.getMap().put(3, new ResultItem(third.getId(), coin));
					}
				}
			}
			else{
				{
					Player third = this.seatPlayingMap.get(3).getMaster().get().getPlayer();
					long coin = (singleResult * 2);
					CoinManager.changeCoin(third, coin, gameResultRecord.getBatchNo(), "当地主赢了,对方有投降");
					gameResultRecord.getMap().put(3, new ResultItem(third.getId(), coin));
				}
				{
					Player first = this.seatPlayingMap.get(1).getMaster().get().getPlayer();
					if(surrenderPosition == 1) {
						long coin = -(singleResult * 2);
						CoinManager.changeCoin(first, coin, gameResultRecord.getBatchNo(), "当农民输了,我投降了");
						gameResultRecord.getMap().put(1, new ResultItem(first.getId(), coin));
					}
				}
				{
					Player second = this.seatPlayingMap.get(2).getMaster().get().getPlayer();
					if(surrenderPosition == 2) {
						long coin = -(singleResult * 2);
						CoinManager.changeCoin(second, coin, gameResultRecord.getBatchNo(), "当农民输了,我投降了");
						gameResultRecord.getMap().put(2, new ResultItem(second.getId(), coin));
					}
				}
			}
		}
		
		
		else {
			//当地主输了
			if(landlordPosition == 1) {
				{
					Player first = this.seatPlayingMap.get(1).getMaster().get().getPlayer();
					long coin = -(singleResult * 2);
					CoinManager.changeCoin(first, coin, gameResultRecord.getBatchNo(), "当地主输了,我投降了");
					gameResultRecord.getMap().put(1, new ResultItem(first.getId(), coin));
				}
				{
					Player second = this.seatPlayingMap.get(2).getMaster().get().getPlayer();
					long coin = singleResult;
					CoinManager.changeCoin(second, coin, gameResultRecord.getBatchNo(), "当农民赢了,对方有投降");
					gameResultRecord.getMap().put(2, new ResultItem(second.getId(), coin));
				}
				{
					Player third = this.seatPlayingMap.get(3).getMaster().get().getPlayer();
					long coin = singleResult;
					CoinManager.changeCoin(third, coin, gameResultRecord.getBatchNo(), "当农民赢了,对方有投降");
					gameResultRecord.getMap().put(3, new ResultItem(third.getId(), coin));
				}
			}
			else if(landlordPosition == 2) {
				{
					Player second = this.seatPlayingMap.get(2).getMaster().get().getPlayer();
					long coin = -(singleResult * 2);
					CoinManager.changeCoin(second, coin, gameResultRecord.getBatchNo(), "当地主输了,我投降了");
					gameResultRecord.getMap().put(2, new ResultItem(second.getId(), coin));
				}
				{
					Player first = this.seatPlayingMap.get(1).getMaster().get().getPlayer();
					long coin = singleResult;
					CoinManager.changeCoin(first, coin, gameResultRecord.getBatchNo(), "当农民赢了,对方有投降");
					gameResultRecord.getMap().put(1, new ResultItem(first.getId(), coin));
				}
				{
					Player third = this.seatPlayingMap.get(3).getMaster().get().getPlayer();
					long coin = singleResult;
					CoinManager.changeCoin(third, coin, gameResultRecord.getBatchNo(), "当农民赢了,对方有投降");
					gameResultRecord.getMap().put(3, new ResultItem(third.getId(), coin));
				}
			}
			else{
				{
					Player third = this.seatPlayingMap.get(3).getMaster().get().getPlayer();
					long coin = -(singleResult * 2);
					CoinManager.changeCoin(third, coin, gameResultRecord.getBatchNo(), "当地主输了,我投降了");
					gameResultRecord.getMap().put(3, new ResultItem(third.getId(), coin));
				}
				{
					Player first = this.seatPlayingMap.get(1).getMaster().get().getPlayer();
					long coin = singleResult;
					CoinManager.changeCoin(first, coin, gameResultRecord.getBatchNo(), "当农民赢了,对方有投降");
					gameResultRecord.getMap().put(1, new ResultItem(first.getId(), coin));
				}
				{
					Player second = this.seatPlayingMap.get(2).getMaster().get().getPlayer();
					long coin = singleResult;
					CoinManager.changeCoin(second, coin, gameResultRecord.getBatchNo(), "当农民赢了,对方有投降");
					gameResultRecord.getMap().put(2, new ResultItem(second.getId(), coin));
				}
			}
		}
		ResultManager.save(gameResultRecord);
		return gameResultRecord;
	}
	
	
	protected DoubleKind getDoubleKind() {
		DdzGameItem config = getDdzGameItem();
		if(config.getDoubleKind() != null ) {
			return config.getDoubleKind();
		}
		return DoubleKind.exponential;
	}
	protected DdzGameItem getDdzGameItem() {
		return (DdzGameItem)this.gameItem;
	}
	protected DdzDeskItem getDdzDeskItem() {
		return (DdzDeskItem)this.deskItem;
	}
	@Override
	protected GameSeat buildGameSeat(int position){
		return new DdzGameSeat(this, position);
	}
	
}
