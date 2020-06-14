/*
    Calimero 2 - A library for KNX network access
    Copyright (c) 2006, 2019 B. Malinowsky

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tag.KnxSecure;
import tag.KnxnetIP;
import tag.KnxnetIPSequential;
import tuwien.auto.calimero.CloseEvent;
import tuwien.auto.calimero.FrameEvent;
import tuwien.auto.calimero.GroupAddress;
import tuwien.auto.calimero.IndividualAddress;
import tuwien.auto.calimero.KNXException;
import tuwien.auto.calimero.KNXIllegalArgumentException;
import tuwien.auto.calimero.KNXTimeoutException;
import tuwien.auto.calimero.Priority;
import tuwien.auto.calimero.Util;
import tuwien.auto.calimero.cemi.CEMILData;
import tuwien.auto.calimero.knxnetip.Connection;
import tuwien.auto.calimero.knxnetip.Debug;
import tuwien.auto.calimero.knxnetip.KNXnetIPConnection.BlockingMode;
import tuwien.auto.calimero.knxnetip.KNXnetIPRouting;
import tuwien.auto.calimero.knxnetip.KNXnetIPTunnel;
import tuwien.auto.calimero.knxnetip.LostMessageEvent;
import tuwien.auto.calimero.knxnetip.RoutingBusyEvent;
import tuwien.auto.calimero.knxnetip.servicetype.RoutingLostMessage;
import tuwien.auto.calimero.knxnetip.servicetype.TunnelingFeature;
import tuwien.auto.calimero.knxnetip.servicetype.TunnelingFeature.InterfaceFeature;
import tuwien.auto.calimero.link.medium.PLSettings;
import tuwien.auto.calimero.link.medium.TPSettings;

/**
 * @author B. Malinowsky
 */
@KnxnetIP
public class KNXNetworkLinkIPTest
{
	private KNXNetworkLink tnl;
	private KNXNetworkLink rtr;
	private NLListenerImpl ltnl, lrtr;
	private CEMILData frame;
	private CEMILData frame2;
	private CEMILData frameInd;

	private final class NLListenerImpl implements NetworkLinkListener
	{
		volatile CEMILData ind;
		volatile CEMILData con;
		volatile boolean closed;

		@Override
		public void indication(final FrameEvent e)
		{
			assertNotNull(e);
			assertEquals(this == ltnl ? tnl : rtr, e.getSource());
			final CEMILData f = (CEMILData) e.getFrame();
			ind = f;
			assertEquals(CEMILData.MC_LDATA_IND, ind.getMessageCode());
			Debug.printLData(ind);
		}

		@Override
		public void confirmation(final FrameEvent e)
		{
			assertNotNull(e);
			assertEquals(this == ltnl ? tnl : rtr, e.getSource());
			final CEMILData f = (CEMILData) e.getFrame();
			con = f;
			assertEquals(CEMILData.MC_LDATA_CON, f.getMessageCode());
			assertTrue(f.isPositiveConfirmation());
			Debug.printLData(f);
		}

		@Override
		public void linkClosed(final CloseEvent e)
		{
			assertNotNull(e);
			assertEquals(this == ltnl ? tnl : rtr, e.getSource());
			if (closed)
				fail("already closed");
			closed = true;
		}
	}

	@BeforeEach
	void init() throws Exception
	{
		tnl = KNXNetworkLinkIP.newTunnelingLink(Util.getLocalHost(), Util.getServer(), false, TPSettings.TP1);
		rtr = new KNXNetworkLinkIP(KNXNetworkLinkIP.ROUTING, Util.getLocalHost(),
				new InetSocketAddress(InetAddress.getByName(KNXnetIPRouting.DEFAULT_MULTICAST), 0), false,
				TPSettings.TP1);
		ltnl = new NLListenerImpl();
		lrtr = new NLListenerImpl();
		tnl.addLinkListener(ltnl);
		rtr.addLinkListener(lrtr);

		frame = new CEMILData(CEMILData.MC_LDATA_REQ, new IndividualAddress(0), new GroupAddress(0, 0, 1),
				new byte[] { 0, (byte) (0x80 | 1) }, Priority.LOW);
		frame2 = new CEMILData(CEMILData.MC_LDATA_REQ, new IndividualAddress(0), new GroupAddress(0, 0, 1),
				new byte[] { 0, (byte) (0x80 | 0) }, Priority.URGENT);
		frameInd = new CEMILData(CEMILData.MC_LDATA_IND, new IndividualAddress(0), new GroupAddress(0, 0, 1),
				new byte[] { 0, (byte) (0x80 | 0) }, Priority.NORMAL);
	}

	@AfterEach
	void tearDown() throws Exception
	{
		if (tnl != null)
			tnl.close();
		if (rtr != null)
			rtr.close();
	}

	@Test
	void testKNXNetworkLinkIPConstructor() throws KNXException, InterruptedException
	{
		tnl.close();
		try (KNXNetworkLink l = new KNXNetworkLinkIP(100, new InetSocketAddress(0), Util.getServer(), false,
				TPSettings.TP1)) {
			fail("illegal arg");
		}
		catch (final KNXIllegalArgumentException e) {}
		try (KNXNetworkLink l = KNXNetworkLinkIP.newTunnelingLink(new InetSocketAddress(0), Util.getServer(), false,
				TPSettings.TP1)) {}
		catch (final KNXIllegalArgumentException e) {}
	}

	@Test
	void tunnelingLinkFactoryMethod() throws KNXException, InterruptedException
	{
		try (KNXNetworkLink link = KNXNetworkLinkIP.newTunnelingLink(Util.getLocalHost(), Util.getServer(), false,
				TPSettings.TP1)) {}

		if (!Util.TEST_NAT)
			return;
		try (KNXNetworkLink link = KNXNetworkLinkIP.newTunnelingLink(Util.getLocalHost(), Util.getServer(), true,
				TPSettings.TP1)) {}
	}

	@Test
	void newRoutingLink() throws UnknownHostException, KNXException
	{
		try (KNXNetworkLinkIP link = KNXNetworkLinkIP.newRoutingLink((NetworkInterface) null,
				InetAddress.getByName("224.0.23.14"), TPSettings.TP1)) {}
	}

	@Test
	void testAddLinkListener()
	{
		tnl.addLinkListener(ltnl);
		tnl.addLinkListener(ltnl);
	}

	@Test
	void testSetKNXMedium()
	{
		try {
			tnl.setKNXMedium(new PLSettings());
			fail("different medium");
		}
		catch (final KNXIllegalArgumentException e) {}
		final class TPSettingsSubClass extends TPSettings
		{
			TPSettingsSubClass()
			{
				super();
			}
		}
		// replace basetype with subtype
		tnl.setKNXMedium(new TPSettingsSubClass());
		// replace subtype with its supertype
		tnl.setKNXMedium(new TPSettings());

		tnl.setKNXMedium(new TPSettings(new IndividualAddress(200)));
		assertEquals(200, tnl.getKNXMedium().getDeviceAddress().getRawAddress());
	}

	@Test
	void testGetKNXMedium()
	{
		assertTrue(tnl.getKNXMedium() instanceof TPSettings);
		assertEquals(0, tnl.getKNXMedium().getDeviceAddress().getRawAddress());
	}

	@Test
	void testClose() throws InterruptedException, KNXTimeoutException
	{
		assertTrue(tnl.isOpen());
		tnl.close();
		// time for link event notifier
		Thread.sleep(50);
		assertTrue(ltnl.closed);
		assertFalse(tnl.isOpen());
		tnl.close();
		try {
			tnl.send(frame, false);
			fail("we are closed");
		}
		catch (final KNXLinkClosedException e) {}
	}

	@Test
	void testGetHopCount()
	{
		assertEquals(6, rtr.getHopCount());
		rtr.setHopCount(7);
		assertEquals(7, rtr.getHopCount());
		try {
			rtr.setHopCount(-1);
			fail("negative hop count");
		}
		catch (final KNXIllegalArgumentException e) {}
		try {
			rtr.setHopCount(8);
			fail("hop count too big");
		}
		catch (final KNXIllegalArgumentException e) {}
	}

	@Test
	void testRemoveLinkListener()
	{
		tnl.removeLinkListener(ltnl);
		tnl.removeLinkListener(ltnl);
		// should do nothing
		tnl.removeLinkListener(lrtr);
	}

	@Test
	void testSendRequestKNXAddressPriorityByteArray()
		throws InterruptedException, UnknownHostException, KNXException
	{
		doSend(true, new byte[] { 0, (byte) (0x80 | 1) });
		doSend(true, new byte[] { 0, (byte) (0x80 | 0) });
		doSend(false, new byte[] { 0, (byte) (0x80 | 1) });
		doSend(false, new byte[] { 0, (byte) (0x80 | 0) });

		// send an extended PL frame
		final KNXNetworkLink plrtr = new KNXNetworkLinkIP(KNXNetworkLinkIP.ROUTING, Util.getLocalHost(),
				new InetSocketAddress(InetAddress.getByName(KNXnetIPRouting.DEFAULT_MULTICAST), 0), false,
				new PLSettings());
		plrtr.sendRequest(new GroupAddress(0, 0, 1), Priority.LOW,
				new byte[] { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) (0x80 | 0) });
		plrtr.close();
	}

	private void doSend(final boolean tunnel, final byte[] nsdu)
		throws KNXLinkClosedException, InterruptedException, KNXTimeoutException
	{
		@SuppressWarnings("resource")
		final KNXNetworkLink lnk = tunnel ? tnl : rtr;
		final NLListenerImpl l = tunnel ? ltnl : lrtr;
		l.con = null;
		lnk.sendRequest(new GroupAddress(0, 0, 1), Priority.LOW, nsdu);
		Thread.sleep(100);
		if (tunnel) {
			assertNotNull(l.con);
		}
	}

	@Test
	void testSendRequestCEMILData() throws KNXLinkClosedException, KNXTimeoutException
	{
		ltnl.con = null;
		tnl.send(frame2, false);
		try {
			Thread.sleep(100);
		}
		catch (final InterruptedException e) {}
		assertNotNull(ltnl.con);

		rtr.send(frameInd, false);
		try {
			Thread.sleep(100);
		}
		catch (final InterruptedException e) {}
	}

	@Test
	void testSendRequestWaitKNXAddressPriorityByteArray()
		throws KNXTimeoutException, KNXLinkClosedException
	{
		doSendWait(true, new byte[] { 0, (byte) (0x80 | 1) });
		doSendWait(true, new byte[] { 0, (byte) (0x80 | 0) });
		doSendWait(false, new byte[] { 0, (byte) (0x80 | 1) });
		doSendWait(false, new byte[] { 0, (byte) (0x80 | 0) });
	}

	private void doSendWait(final boolean tunnel, final byte[] nsdu) throws KNXLinkClosedException, KNXTimeoutException
	{
		@SuppressWarnings("resource")
		final KNXNetworkLink lnk = tunnel ? tnl : rtr;
		final NLListenerImpl l = tunnel ? ltnl : lrtr;
		l.con = null;
		lnk.sendRequestWait(new GroupAddress(0, 0, 1), Priority.LOW, nsdu);
		// even in router mode, we still get tunnel ind., so always wait
		try {
			Thread.sleep(100);
		}
		catch (final InterruptedException e) {}
		if (tunnel) {
			assertNotNull(l.con);
		}
	}

	@Test
	void testSendRequestWaitCEMILData() throws KNXTimeoutException, KNXLinkClosedException
	{
		ltnl.con = null;
		try {
			tnl.send(frameInd, true);
			fail("should get timeout because of wrong frame");
		}
		catch (final KNXTimeoutException e) {}
		assertNull(ltnl.con);
		tnl.send(frame, true);
		try {
			Thread.sleep(50);
		}
		catch (final InterruptedException e1) {}
		assertNotNull(ltnl.con);
		rtr.send(frameInd, true);
		try {
			Thread.sleep(100);
		}
		catch (final InterruptedException e) {}
	}

	@Test
	void testGetName() throws KNXException
	{
		String n = tnl.getName();
		assertTrue(n.indexOf(Util.getServer().getAddress().getHostAddress()) > -1);
		tnl.close();
		n = tnl.getName();
		assertNotNull(n);
		assertTrue(n.indexOf(Util.getServer().getAddress().getHostAddress()) > -1);

		n = rtr.getName();
		assertTrue(n.indexOf(KNXnetIPRouting.DEFAULT_MULTICAST) > -1);
//		assertTrue(n.indexOf("link") > -1);
		rtr.close();
		n = rtr.getName();
		assertNotNull(n);
		assertTrue(n.indexOf(KNXnetIPRouting.DEFAULT_MULTICAST) > -1);
	}

	@Test
	@KnxnetIPSequential
	void runNotifierOnlyAfterEstablishingConnection()
	{
		// create some links that fail during construction
		final InetSocketAddress sa = new InetSocketAddress(0);
		assertThrows(KNXException.class, () -> KNXNetworkLinkIP.newTunnelingLink(sa, sa, false, TPSettings.TP1),
				"no KNXnet/IP server with wildcard IP");
		assertThrows(KNXException.class, () -> KNXNetworkLinkIP.newTunnelingLink(sa,
				new InetSocketAddress("1.0.0.1", 3671), false, TPSettings.TP1), "no KNXnet/IP server with that IP");

		final Thread[] threads = new Thread[Thread.activeCount() + 10];
		final int active = Thread.enumerate(threads);
		assertTrue(active <= threads.length);

		final List<Thread> list = Arrays.asList(threads).subList(0, active);
		final long cnt = list.stream().map(Thread::getName).filter(s -> s.equals("Calimero link notifier")).count();
		// we should only have our two initial link notifiers running, not the failed ones
		assertEquals(2, cnt, "running notifiers");
	}

	@Test
	@KnxSecure
	void secureRoutingLink() throws KNXException, SocketException {
		final NetworkInterface netif = Util.localInterface();
		final byte[] groupKey = new byte[16];
		try (KNXNetworkLink link = KNXNetworkLinkIP.newSecureRoutingLink(netif, KNXNetworkLinkIP.DefaultMulticast, groupKey,
				Duration.ofMillis(2000), TPSettings.TP1)) {}
	}

	@Test
	@KnxSecure
	void secureRoutingInvalidKeyLength() throws SocketException {
		final NetworkInterface netif = Util.localInterface();
		final byte[] groupKey = new byte[10];
		assertThrows(KNXIllegalArgumentException.class, () -> KNXNetworkLinkIP.newSecureRoutingLink(netif,
				KNXNetworkLinkIP.DefaultMulticast, groupKey, Duration.ofMillis(2000), TPSettings.TP1));
	}

	private static interface DefaultNetworkLinkListener extends NetworkLinkListener {
		@Override
		default void linkClosed(final CloseEvent e) {}

		@Override
		default void indication(final FrameEvent e) {}

		@Override
		default void confirmation(final FrameEvent e) {}
	}

	private static interface DefaultMethodEvent extends DefaultNetworkLinkListener {
		@LinkEvent
		default void tunnelingFeature(final TunnelingFeature feature) { System.out.println("default method event " + feature); }
	}

	@Test
	void registerTunnelingFeatureEvents() throws KNXException, InterruptedException {
		final var listener = new DefaultNetworkLinkListener() {
			@LinkEvent
			void receivedTunnelingFeature(final TunnelingFeature feature) {
				System.out.println("received " + feature);
			}
		};
		final var connection = Connection.newTcpConnection(new InetSocketAddress(0), Util.getServer());
		try (final var link = KNXNetworkLinkIP.newTunnelingLink(connection, TPSettings.TP1)) {
			link.addLinkListener(listener);

			((KNXnetIPTunnel) link.conn).send(InterfaceFeature.ConnectionStatus);
			((KNXnetIPTunnel) link.conn).send(InterfaceFeature.IndividualAddress);
		}
	}

	@Test
	void registerRoutingEvents() throws KNXException, InterruptedException, SocketException {
		try (final var link = KNXNetworkLinkIP.newRoutingLink(Util.localInterface(), KNXNetworkLinkIP.DefaultMulticast, TPSettings.TP1)) {
			final var listener = new DefaultNetworkLinkListener() {
				@LinkEvent
				void lostMessage(final LostMessageEvent lostMessage) { System.out.println(lostMessage); }

				@LinkEvent
				void routingBusy(final RoutingBusyEvent busy) { System.out.println(busy); }
			};
			link.addLinkListener(listener);

			int i = 0;
			while (i++ < 1000) {
				link.conn.send(frameInd, BlockingMode.NonBlocking);
			}
		}
	}

	@Test
	void registerUnsupportedEventType() throws KNXException, SocketException {
		try (final var link = KNXNetworkLinkIP.newRoutingLink(Util.localInterface(), KNXNetworkLinkIP.DefaultMulticast, TPSettings.TP1)) {
			link.addLinkListener(new DefaultNetworkLinkListener() {
				@LinkEvent
				void unsupportedEventType(final RoutingLostMessage lostMessage) { fail("unsupported event type"); }
			});
		}
	}

	@Test
	void registerDefaultMethodEvent() throws KNXException, InterruptedException {
		final var connection = Connection.newTcpConnection(new InetSocketAddress(0), Util.getServer());
		try (final var link = KNXNetworkLinkIP.newTunnelingLink(connection, TPSettings.TP1)) {
			link.addLinkListener(new DefaultMethodEvent() {});

			((KNXnetIPTunnel) link.conn).send(InterfaceFeature.ConnectionStatus);
			((KNXnetIPTunnel) link.conn).send(InterfaceFeature.IndividualAddress);
		}
	}

	@Test
	void registerConcreteMethodOverridingDefaultMethod() throws KNXException, InterruptedException {
		final var connection = Connection.newTcpConnection(new InetSocketAddress(0), Util.getServer());
		try (final var link = KNXNetworkLinkIP.newTunnelingLink(connection, TPSettings.TP1)) {
			final var concreteEvent = new DefaultMethodEvent() {
				@Override
				@LinkEvent
				public void tunnelingFeature(final TunnelingFeature feature) {
					System.out.println("concrete method event " + feature);
				}
			};
			link.addLinkListener(concreteEvent);

			((KNXnetIPTunnel) link.conn).send(InterfaceFeature.ConnectionStatus);
			((KNXnetIPTunnel) link.conn).send(InterfaceFeature.IndividualAddress);
		}
	}
}
