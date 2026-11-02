// SPDX-License-Identifier: MIT
pragma solidity 0.8.38;

import {Test} from "forge-std/Test.sol";
import {IERC20Errors} from "@openzeppelin/contracts/interfaces/draft-IERC6093.sol";
import {MockToken} from "../src/MockToken.sol";

contract MockTokenTest is Test {
    MockToken private teth;
    MockToken private tusd;

    address private alice;
    address private bob;
    address private spender;

    uint256 private constant TETH_SUPPLY = 1_000 * 10 ** 18;
    uint256 private constant TUSD_SUPPLY = 1_000_000 * 10 ** 6;

    function setUp() public {
        alice = makeAddr("alice");
        bob = makeAddr("bob");
        spender = makeAddr("spender");

        teth = new MockToken("Mini CEX Mock Ether", "TETH", 18, alice, TETH_SUPPLY);

        tusd = new MockToken("Mini CEX Mock USD", "TUSD", 6, alice, TUSD_SUPPLY);
    }

    function testMetadataAndInitialSupply() public view {
        assertEq(teth.name(), "Mini CEX Mock Ether");
        assertEq(teth.symbol(), "TETH");
        assertEq(teth.decimals(), 18);
        assertEq(teth.totalSupply(), TETH_SUPPLY);
        assertEq(teth.balanceOf(alice), TETH_SUPPLY);

        assertEq(tusd.name(), "Mini CEX Mock USD");
        assertEq(tusd.symbol(), "TUSD");
        assertEq(tusd.decimals(), 6);
        assertEq(tusd.totalSupply(), TUSD_SUPPLY);
        assertEq(tusd.balanceOf(alice), TUSD_SUPPLY);
    }

    function testTransferUsesExactUnits() public {
        // 1.234567 TUSD.
        uint256 units = 1_234_567;

        vm.prank(alice);
        bool success = tusd.transfer(bob, units);

        assertTrue(success);
        assertEq(tusd.balanceOf(bob), units);
        assertEq(tusd.balanceOf(alice), TUSD_SUPPLY - units);
        assertEq(tusd.totalSupply(), TUSD_SUPPLY);
    }

    function testApprovedSpenderCanTransfer() public {
        uint256 approvedUnits = 100 * 10 ** 6;
        uint256 transferredUnits = 30 * 10 ** 6;

        vm.prank(alice);
        tusd.approve(spender, approvedUnits);

        vm.prank(spender);
        bool success = tusd.transferFrom(alice, bob, transferredUnits);

        assertTrue(success);
        assertEq(tusd.balanceOf(bob), transferredUnits);
        assertEq(tusd.balanceOf(alice), TUSD_SUPPLY - transferredUnits);
        assertEq(tusd.allowance(alice, spender), approvedUnits - transferredUnits);
    }

    function testTransferFromWithoutApprovalReverts() public {
        uint256 units = 10 ** 6;

        vm.expectRevert(abi.encodeWithSelector(IERC20Errors.ERC20InsufficientAllowance.selector, spender, 0, units));

        vm.prank(spender);
        tusd.transferFrom(alice, bob, units);

        assertEq(tusd.balanceOf(alice), TUSD_SUPPLY);
        assertEq(tusd.balanceOf(bob), 0);
    }

    function testTransferAboveBalanceReverts() public {
        uint256 units = TUSD_SUPPLY + 1;

        vm.expectRevert(
            abi.encodeWithSelector(IERC20Errors.ERC20InsufficientBalance.selector, alice, TUSD_SUPPLY, units)
        );

        vm.prank(alice);
        tusd.transfer(bob, units);

        assertEq(tusd.balanceOf(alice), TUSD_SUPPLY);
        assertEq(tusd.balanceOf(bob), 0);
    }

    function testUnsupportedDecimalsRevert() public {
        vm.expectRevert(abi.encodeWithSelector(MockToken.UnsupportedDecimals.selector, uint8(19)));

        new MockToken("Invalid Mock", "BAD", 19, alice, 1);
    }

    function testZeroInitialHolderReverts() public {
        vm.expectRevert(abi.encodeWithSelector(IERC20Errors.ERC20InvalidReceiver.selector, address(0)));

        new MockToken("Invalid Mock", "BAD", 6, address(0), 1);
    }

    function testFuzzTransferPreservesSupply(uint256 rawUnits) public {
        uint256 units = bound(rawUnits, 0, TETH_SUPPLY);

        vm.prank(alice);
        teth.transfer(bob, units);

        assertEq(teth.balanceOf(alice), TETH_SUPPLY - units);
        assertEq(teth.balanceOf(bob), units);
        assertEq(teth.balanceOf(alice) + teth.balanceOf(bob), TETH_SUPPLY);
        assertEq(teth.totalSupply(), TETH_SUPPLY);
    }
}
