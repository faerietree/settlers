/*
 * Copyright (c) 2017
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"),
 * to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 */

package jsettlers.main.android.mainmenu.gamesetup.playeritem;

import java.util.Objects;
import jsettlers.logic.player.PlayerSetting;
import jsettlers.main.android.mainmenu.gamesetup.PlayerSlotView;

/**
 * Created by tompr on 18/02/2017.
 */
public class PlayerSlotPresenter {
	private final PositionChangedListener positionChangedListener;

	private PlayerSlotView view;

	private String name;
	private boolean ready = false;
	private boolean showReadyControl = false;
	private boolean controlsEnabled = true;
	private SlotStateListener slotStateListener;
	private boolean informReady;
	private boolean informOther;

	private Civilisation[] possibleCivilisations;
	private Civilisation civilisation;

	private PlayerType[] possiblePlayerTypes;
	private PlayerType playerType;

	private StartPosition[] possibleStartPositions;
	private StartPosition startPosition;

	private Team[] possibleTeams;
	private Team team;

	private final byte slot;

	public PlayerSlotPresenter(byte slot, PositionChangedListener positionChangedListener) {
		this.slot = slot;
		this.positionChangedListener = positionChangedListener;
	}

	public void initView(PlayerSlotView view) {
		this.view = view;

		view.setName(name);
		view.setReady(ready);

		if (showReadyControl) {
			view.showReadyControl();
		} else {
			view.hideReadyControl();
		}

		if(slotStateListener != null) {
			view.setReadyControlsEnabled();
		} else {
			view.setReadyControlsDisabled();
		}

		if (controlsEnabled) {
			view.setPlayerControlsEnabled();
		} else {
			view.setPlayerControlsDisabled();
		}

		view.setPossibleCivilisations(possibleCivilisations);
		view.setCivilisation(civilisation);

		view.setPossibleStartPositions(possibleStartPositions);
		view.setStartPosition(startPosition);

		view.setPossibleTeams(possibleTeams);
		view.setTeam(team);

		view.setPossiblePlayerTypes(possiblePlayerTypes);
		view.setPlayerType(playerType);
	}

	public PlayerSetting getPlayerSettings() {
		return new PlayerSetting(playerType.getType(), civilisation.getType(), team.asByte());
	}

	/**
	 * Civilisations
	 */
	public void setPossibleCivilisations(Civilisation[] possibleCivilisations) {
		this.possibleCivilisations = possibleCivilisations;
	}

	public void civilisationSelected(Civilisation civilisation) {
		Civilisation oldCivilisation = this.civilisation;
		setCivilisation(civilisation);

		if(!Objects.equals(civilisation, oldCivilisation) && informOther) {
			slotStateListener.civilisationChanged(slot, civilisation.getType());
		}
	}

	public void setCivilisation(Civilisation civilisation) {
		this.civilisation = civilisation;
	}

	/**
	 * Players types
	 */
	public void setPossiblePlayerTypes(PlayerType[] ePlayerTypes) {
		this.possiblePlayerTypes = ePlayerTypes;
	}

	public void playerTypeSelected(PlayerType playerType) {
		PlayerType oldPlayerType = this.playerType;
		setPlayerType(playerType);

		if(!Objects.equals(playerType, oldPlayerType) && informOther) {
			slotStateListener.playerTypeChanged(slot, playerType.getType());
		}
	}

	public void setPlayerType(PlayerType playerType) {
		this.playerType = playerType;
	}

	/**
	 * Start positions
	 */
	public void setPossibleStartPositions(int numberOfPlayers) {
		possibleStartPositions = new StartPosition[numberOfPlayers];
		for (byte i = 0; i < numberOfPlayers; i++) {
			possibleStartPositions[i] = new StartPosition(i);
		}
	}

	public void setStartPosition(StartPosition startPosition) {
		this.startPosition = startPosition;
	}

	public void startPositionSelected(StartPosition position) {
		positionChangedListener.positionChanged(this, this.startPosition, position);

		StartPosition oldStartPosition = this.startPosition;
		setStartPosition(position);

		if(!Objects.equals(position, oldStartPosition) && informOther) {
			slotStateListener.positionChanged(slot, position.asByte());
		}
	}

	public StartPosition getStartPosition() {
		return startPosition;
	}

	public byte getPlayerId() {
		return startPosition.asByte();
	}

	/**
	 * Teams
	 */
	public void setPossibleTeams(int numberOfPlayers) {
		possibleTeams = new Team[numberOfPlayers];
		for (byte i = 0; i < numberOfPlayers; i++) {
			possibleTeams[i] = new Team(i);
		}
	}

	public void setTeam(Team team) {
		this.team = team;
	}

	public void teamSelected(Team team) {
		Team oldTeam = this.team;
		setTeam(team);

		if(!Objects.equals(oldTeam, team) && informOther) {
			slotStateListener.teamChanged(slot, team.asByte());
		}
	}

	/**
	 * Name
	 */
	public void setName(String name) {
		this.name = name;
	}

	/**
	 * Ready
	 */
	public void setReady(boolean ready) {
		this.ready = ready;
	}

	public void readyChanged(boolean ready) {
		if (informReady)
			slotStateListener.readyChanged(ready);
	}

	public void setShowReadyControl(boolean showReadyControl) {
		this.showReadyControl = showReadyControl;
	}

	public void setSlotStateListener(SlotStateListener slotStateListener, boolean informReady, boolean informOther) {
		this.slotStateListener = slotStateListener;
		this.informReady = informReady;
		this.informOther = informOther;
	}

	/**
	 * Controls enable
	 */
	public void setControlsEnabled(boolean enabled) {
		controlsEnabled = enabled;
	}
}
