/*******************************************************************************
 * Copyright (c) 2015 - 2017
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
 *******************************************************************************/
package jsettlers.logic.map.grid.partition.manager.materials.offers;

import java.io.Serializable;

import jsettlers.common.position.ILocatable;
import jsettlers.common.position.ShortPoint2D;
import jsettlers.logic.map.grid.partition.manager.materials.offers.list.IListManageable;
import jsettlers.logic.map.grid.partition.manager.materials.offers.list.IPrioritizable;
import jsettlers.logic.map.grid.partition.manager.materials.MaterialsManager;

/**
 * This class is used by {@link MaterialsManager} to store offers of materials.
 *
 * @author Andreas Eberle
 *
 */
public final class MaterialOffer implements Serializable, ILocatable, IPrioritizable<EOfferPriority>, IListManageable {
	private static final long serialVersionUID = 8516955442065220998L;

	private final ShortPoint2D   position;
	private       EOfferPriority priority;
	private byte amount = 0;

	MaterialOffer(ShortPoint2D position, EOfferPriority priority, byte amount) {
		this.position = position;
		this.priority = priority;
		this.amount = amount;
	}

	@Override
	public ShortPoint2D getPos() {
		return position;
	}

	/**
	 * Increases the amount and returns the new value.
	 *
	 * @return
	 */
	public byte incrementAmount() {
		return ++amount;
	}

	/**
	 * Decreases the amount and returns the new value.
	 *
	 * @return
	 */
	public void decrementAmount() {
		--amount;
	}

	public byte getAmount() {
		return amount;
	}

	@Override
	public EOfferPriority getPriority() {
		return priority;
	}

	@Override
	public void updatePriority(EOfferPriority priority) {
		this.priority = priority;
	}

	@Override
	public boolean isActive() {
		return amount > 0;
	}

	@Override
	public boolean canBeRemoved() {
		return amount <= 0;
	}

	@Override
	public String toString() {
		return "MaterialOffer{" + "position=" + position + ", priority=" + priority + ", amount=" + amount + '}';
	}
}
