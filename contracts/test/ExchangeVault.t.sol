// SPDX-License-Identifier: MIT
pragma solidity 0.8.38;

import {Test} from "forge-std/Test.sol";
import {Vm} from "forge-std/Vm.sol";
import {IERC20Errors} from "@openzeppelin/contracts/interfaces/draft-IERC6093.sol";
import {ReentrancyGuardTransient} from "@openzeppelin/contracts/utils/ReentrancyGuardTransient.sol";

import {MockToken} from "../src/MockToken.sol";
import {ExchangeVault} from "../src/ExchangeVault.sol";

contract ExchangeVaultTest is Test {
    MockToken private teth;
    MockToken private tusd;
    ExchangeVault private vault;

    address private operator;
    address private alice;
    address private bob;

    uint256 private constant UNIT = 10 ** 6;
    uint256 private constant SUPPLY = 1_000_000 * UNIT;

    bytes16 private constant ACCOUNT_ID = hex"00112233445566778899aabbccddeeff";

    bytes32 private constant WITHDRAWAL_ID = keccak256("withdrawal-1");

    event Deposited(bytes16 indexed accountId, address indexed token, address indexed sender, uint256 amount);

    event Withdrawn(bytes32 indexed withdrawalId, address indexed token, address indexed recipient, uint256 amount);

    function setUp() public {
        operator = makeAddr("operator");
        alice = makeAddr("alice");
        bob = makeAddr("bob");

        teth = new MockToken("Mini CEX Mock Ether", "TETH", 18, alice, 1_000 ether);

        tusd = new MockToken("Mini CEX Mock USD", "TUSD", 6, alice, SUPPLY);

        vault = new ExchangeVault(operator, address(teth), address(tusd));
    }

    function testConstructorRejectsInvalidAddresses() public {
        vm.expectRevert(ExchangeVault.InvalidConfiguration.selector);
        new ExchangeVault(address(0), address(teth), address(tusd));

        vm.expectRevert(ExchangeVault.InvalidConfiguration.selector);
        new ExchangeVault(operator, address(teth), address(teth));

        vm.expectRevert(ExchangeVault.InvalidConfiguration.selector);
        new ExchangeVault(operator, alice, address(tusd));

        vm.expectRevert(ExchangeVault.InvalidConfiguration.selector);
        new ExchangeVault(operator, address(teth), address(0));
    }

    function testDepositTUSDAndEvent() public {
        uint256 amount = 100 * UNIT;

        vm.prank(alice);
        tusd.approve(address(vault), amount);

        vm.expectEmit(true, true, true, true, address(vault));
        emit Deposited(ACCOUNT_ID, address(tusd), alice, amount);

        vm.prank(alice);
        vault.deposit(address(tusd), amount, ACCOUNT_ID);

        assertEq(tusd.balanceOf(address(vault)), amount);
        assertEq(tusd.balanceOf(alice), SUPPLY - amount);
        assertEq(tusd.allowance(alice, address(vault)), 0);
    }

    function testDepositTETH() public {
        _deposit(teth, 2 ether);

        assertEq(teth.balanceOf(address(vault)), 2 ether);
        assertEq(teth.balanceOf(alice), 998 ether);
    }

    function testDepositNeedsApproval() public {
        vm.expectRevert(
            abi.encodeWithSelector(IERC20Errors.ERC20InsufficientAllowance.selector, address(vault), 0, UNIT)
        );

        vm.prank(alice);
        vault.deposit(address(tusd), UNIT, ACCOUNT_ID);

        assertEq(tusd.balanceOf(address(vault)), 0);
    }

    function testDepositRejectsInvalidInput() public {
        vm.startPrank(alice);

        vm.expectRevert(ExchangeVault.InvalidAmount.selector);
        vault.deposit(address(tusd), 0, ACCOUNT_ID);

        vm.expectRevert(ExchangeVault.InvalidAccountId.selector);
        vault.deposit(address(tusd), UNIT, bytes16(0));

        vm.expectRevert(ExchangeVault.UnsupportedToken.selector);
        vault.deposit(address(0), UNIT, ACCOUNT_ID);

        vm.stopPrank();
    }

    function testOnlyOperatorCanWithdraw() public {
        _deposit(tusd, 100 * UNIT);

        vm.expectRevert(ExchangeVault.UnauthorizedOperator.selector);
        vm.prank(alice);
        vault.withdraw(WITHDRAWAL_ID, address(tusd), bob, UNIT);

        assertFalse(vault.processedWithdrawals(WITHDRAWAL_ID));
        assertEq(tusd.balanceOf(address(vault)), 100 * UNIT);
        assertEq(tusd.balanceOf(bob), 0);
    }

    function testWithdrawalAndEvent() public {
        _deposit(tusd, 100 * UNIT);

        vm.expectEmit(true, true, true, true, address(vault));
        emit Withdrawn(WITHDRAWAL_ID, address(tusd), bob, 30 * UNIT);

        _withdraw(WITHDRAWAL_ID, address(tusd), bob, 30 * UNIT);

        assertTrue(vault.processedWithdrawals(WITHDRAWAL_ID));
        assertEq(tusd.balanceOf(address(vault)), 70 * UNIT);
        assertEq(tusd.balanceOf(bob), 30 * UNIT);
    }

    function testWithdrawalIdCannotBeReused() public {
        _deposit(tusd, 100 * UNIT);
        _deposit(teth, 2 ether);

        _withdraw(WITHDRAWAL_ID, address(tusd), bob, UNIT);

        // Changing the token and recipient cannot bypass deduplication.
        vm.expectRevert(ExchangeVault.WithdrawalAlreadyProcessed.selector);
        vm.prank(operator);
        vault.withdraw(WITHDRAWAL_ID, address(teth), alice, 1 ether);

        assertEq(tusd.balanceOf(bob), UNIT);
        assertEq(teth.balanceOf(address(vault)), 2 ether);
    }

    function testWithdrawalCanRetrySameIdAfterRevert() public {
        _deposit(tusd, UNIT);

        vm.expectRevert(
            abi.encodeWithSelector(IERC20Errors.ERC20InsufficientBalance.selector, address(vault), UNIT, 2 * UNIT)
        );

        vm.prank(operator);
        vault.withdraw(WITHDRAWAL_ID, address(tusd), bob, 2 * UNIT);

        assertFalse(vault.processedWithdrawals(WITHDRAWAL_ID));
        assertEq(tusd.balanceOf(bob), 0);

        _deposit(tusd, UNIT);
        _withdraw(WITHDRAWAL_ID, address(tusd), bob, 2 * UNIT);

        assertTrue(vault.processedWithdrawals(WITHDRAWAL_ID));
        assertEq(tusd.balanceOf(address(vault)), 0);
        assertEq(tusd.balanceOf(bob), 2 * UNIT);
    }

    function testWithdrawalRejectsInvalidInput() public {
        vm.startPrank(operator);

        vm.expectRevert(ExchangeVault.InvalidWithdrawalId.selector);
        vault.withdraw(bytes32(0), address(tusd), bob, UNIT);

        vm.expectRevert(ExchangeVault.InvalidAmount.selector);
        vault.withdraw(WITHDRAWAL_ID, address(tusd), bob, 0);

        vm.expectRevert(ExchangeVault.InvalidRecipient.selector);
        vault.withdraw(WITHDRAWAL_ID, address(tusd), address(0), UNIT);

        vm.expectRevert(ExchangeVault.InvalidRecipient.selector);
        vault.withdraw(WITHDRAWAL_ID, address(tusd), address(vault), UNIT);

        vm.expectRevert(ExchangeVault.UnsupportedToken.selector);
        vault.withdraw(WITHDRAWAL_ID, address(0), bob, UNIT);

        vm.stopPrank();

        assertFalse(vault.processedWithdrawals(WITHDRAWAL_ID));
    }

    function testDirectTransferDoesNotEmitDeposit() public {
        vm.recordLogs();

        vm.prank(alice);
        tusd.transfer(address(vault), UNIT);

        Vm.Log[] memory logs = vm.getRecordedLogs();

        for (uint256 i = 0; i < logs.length; i++) {
            assertTrue(logs[i].emitter != address(vault));
        }

        assertEq(tusd.balanceOf(address(vault)), UNIT);
    }

    function testFeeOnTransferDepositReverts() public {
        FeeToken fee = new FeeToken(alice);

        ExchangeVault feeVault = new ExchangeVault(operator, address(teth), address(fee));

        vm.prank(alice);
        fee.approve(address(feeVault), 100);

        vm.expectRevert(ExchangeVault.UnexpectedTokenBalance.selector);
        vm.prank(alice);
        feeVault.deposit(address(fee), 100, ACCOUNT_ID);

        // The fee and transfer are rolled back too.
        assertEq(fee.balanceOf(alice), 1_000);
        assertEq(fee.balanceOf(address(feeVault)), 0);
        assertEq(fee.totalSupply(), 1_000);
    }

    function testFeeOnTransferWithdrawalReverts() public {
        FeeToken fee = new FeeToken(alice);

        ExchangeVault feeVault = new ExchangeVault(operator, address(teth), address(fee));

        vm.prank(alice);
        fee.transfer(address(feeVault), 100);

        // The test token burns one unit during this direct transfer.
        assertEq(fee.balanceOf(address(feeVault)), 99);

        vm.expectRevert(ExchangeVault.UnexpectedTokenBalance.selector);
        vm.prank(operator);
        feeVault.withdraw(WITHDRAWAL_ID, address(fee), bob, 10);

        assertFalse(feeVault.processedWithdrawals(WITHDRAWAL_ID));
        assertEq(fee.balanceOf(address(feeVault)), 99);
        assertEq(fee.balanceOf(bob), 0);
        assertEq(fee.totalSupply(), 999);
    }

    function testReentrancyBlocked() public {
        ReenteringToken token = new ReenteringToken(alice);

        ExchangeVault target = new ExchangeVault(operator, address(teth), address(token));

        token.setTarget(target);

        vm.prank(alice);
        token.approve(address(target), 100);

        vm.prank(alice);
        target.deposit(address(token), 100, ACCOUNT_ID);

        assertTrue(token.nestedError() == ReentrancyGuardTransient.ReentrancyGuardReentrantCall.selector);

        assertEq(token.balanceOf(address(target)), 100);
        assertEq(token.balanceOf(alice), 900);
    }

    function testFuzzDepositAndWithdrawalConserveTokens(uint256 rawDeposit, uint256 rawWithdrawal) public {
        uint256 deposited = bound(rawDeposit, 1, SUPPLY);
        uint256 withdrawn = bound(rawWithdrawal, 1, deposited);

        _deposit(tusd, deposited);
        _withdraw(WITHDRAWAL_ID, address(tusd), bob, withdrawn);

        assertEq(tusd.balanceOf(alice), SUPPLY - deposited);
        assertEq(tusd.balanceOf(address(vault)), deposited - withdrawn);
        assertEq(tusd.balanceOf(bob), withdrawn);

        assertEq(tusd.balanceOf(alice) + tusd.balanceOf(address(vault)) + tusd.balanceOf(bob), SUPPLY);

        assertEq(tusd.totalSupply(), SUPPLY);
        assertTrue(vault.processedWithdrawals(WITHDRAWAL_ID));
    }

    function _deposit(MockToken token, uint256 amount) private {
        vm.startPrank(alice);
        token.approve(address(vault), amount);
        vault.deposit(address(token), amount, ACCOUNT_ID);
        vm.stopPrank();
    }

    function _withdraw(bytes32 withdrawalId, address token, address recipient, uint256 amount) private {
        vm.prank(operator);
        vault.withdraw(withdrawalId, token, recipient, amount);
    }
}

/// @dev Test-only token that burns one unit on each nonzero transfer.
contract FeeToken is MockToken {
    constructor(address holder) MockToken("Fee Test", "FEE", 6, holder, 1_000) {}

    function _update(address from, address to, uint256 value) internal override {
        if (from != address(0) && to != address(0) && value > 0) {
            super._update(from, to, value - 1);
            super._update(from, address(0), 1);
        } else {
            super._update(from, to, value);
        }
    }
}

/// @dev Test-only token that attempts a nested deposit.
contract ReenteringToken is MockToken {
    ExchangeVault private target;
    bytes4 public nestedError;

    constructor(address holder) MockToken("Reentry Test", "REENTRY", 6, holder, 1_000) {}

    function setTarget(ExchangeVault target_) external {
        target = target_;
    }

    function transferFrom(address from, address to, uint256 amount) public override returns (bool) {
        (bool success, bytes memory reason) =
            address(target).call(abi.encodeCall(ExchangeVault.deposit, (address(this), 1, bytes16(uint128(1)))));

        require(!success, "Nested deposit unexpectedly succeeded");
        nestedError = bytes4(reason);

        return super.transferFrom(from, to, amount);
    }
}
