/*
    Calimero 2 - A library for KNX network access
    Copyright (c) 2019 B. Malinowsky

    This program is free software; you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation; either version 2 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program; if not, write to the Free Software
    Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA

    Linking this library statically or dynamically with other modules is
    making a combined work based on this library. Thus, the terms and
    conditions of the GNU General Public License cover the whole
    combination.

    As a special exception, the copyright holders of this library give you
    permission to link this library with independent modules to produce an
    executable, regardless of the license terms of these independent
    modules, and to copy and distribute the resulting executable under terms
    of your choice, provided that you also meet, for each linked independent
    module, the terms and conditions of the license of that module. An
    independent module is a module which is not derived from or based on
    this library. If you modify this library, you may extend this exception
    to your version of the library, but you are not obligated to do so. If
    you do not wish to do so, delete this exception statement from your
    version.
*/

package tuwien.auto.calimero.link;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import tuwien.auto.calimero.KNXException;
import tuwien.auto.calimero.KNXTimeoutException;
import tuwien.auto.calimero.baos.BaosService;
import tuwien.auto.calimero.baos.BaosService.Property;
import tuwien.auto.calimero.baos.BaosService.Timer;
import tuwien.auto.calimero.link.medium.TPSettings;
import tuwien.auto.calimero.serial.KNXPortClosedException;
import tuwien.auto.calimero.serial.usb.HidReport;
import tuwien.auto.calimero.serial.usb.UsbConnection.EmiType;

@Execution(ExecutionMode.SAME_THREAD)
class KNXNetworkLinkUsbTest {

	// link switches to baos mode during creation
	private class BaosLinkUsb extends KNXNetworkLinkUsb {
		private final EmiType activeEmi = EmiType.CEmi;

		BaosLinkUsb() throws KNXException, InterruptedException {
			super("", TPSettings.TP1);
			baosMode();
		}

		void send(final BaosService service) throws KNXPortClosedException, KNXTimeoutException {
			logger.debug("send {}", service);
			conn.send(HidReport.create(activeEmi.emi, service.toByteArray()).get(0), true);
			try {
				Thread.sleep(100);
			}
			catch (final InterruptedException e) {}
		}
	}

	private BaosLinkUsb link;

	@BeforeEach
	void init() throws KNXException, InterruptedException {
		link = new BaosLinkUsb();
	}

	@AfterEach
	void shutDown() {
		link.close();
	}

	@Test
	void readBaosProperties() throws KNXException {
		for (final var property : Property.values()) {
			final int id = property.id();
			if (id == 0)
				continue;
			final var service = BaosService.getServerItem(id, 1);
			link.send(service);
		}
	}

	@Test
	void readWriteDatapoint() throws KNXException {
		final var setDp = BaosService.setDatapointValue(Map.of(1, new byte[] { 1 }));
		link.send(setDp);

		final var getDp = BaosService.getDatapointValue(1, 1, 0);
		link.send(getDp);
	}

	@Test
	void oneShotTimer() throws KNXException {
		final byte[] job = { 0, 1, 0, 1, 1 };
		final var time = ZonedDateTime.now().plusSeconds(5);
		final Timer oneShot = Timer.oneShot(1, time, job, "test-timer");
		System.out.println("use " + oneShot);
		final var setTimer = BaosService.setTimer(List.of(oneShot));

		link.send(setTimer);
		link.send(BaosService.getTimer(1, 1));
	}

	@Test
	void parameterByte() throws KNXException {
		final var getParameters = BaosService.getParameter(0, 5);
		link.send(getParameters);
	}
}
