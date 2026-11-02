// SPDX-License-Identifier: MIT
pragma solidity 0.8.38;

import {IERC20} from "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import {SafeERC20} from "@openzeppelin/contracts/token/ERC20/utils/SafeERC20.sol";
import {ReentrancyGuardTransient} from
    "@openzeppelin/contracts/utils/ReentrancyGuardTransient.sol";

/// @notice Custodial vault for the Mini CEX mock assets.
/// @dev User balances and withdrawal authorization live in the backend.
contract ExchangeVault is ReentrancyGuardTransient {
    using SafeERC20 for IERC20;

    error InvalidConfiguration();
    error UnsupportedToken();
    error InvalidAmount();
    error InvalidAccountId();
    error InvalidWithdrawalId();
    error InvalidRecipient();
    error UnauthorizedOperator();
    error WithdrawalAlreadyProcessed();
    error UnexpectedTokenBalance();

    address public immutable operator;
    address public immutable teth;
    address public immutable tusd;

    mapping(bytes32 withdrawalId => bool processed)
        public processedWithdrawals;

    event Deposited(
        bytes16 indexed accountId,
        address indexed token,
        address indexed sender,
        uint256 amount
    );

    event Withdrawn(
        bytes32 indexed withdrawalId,
        address indexed token,
        address indexed recipient,
        uint256 amount
    );

    constructor(address operator_, address teth_, address tusd_) {
        if (
            operator_ == address(0)
                || teth_ == tusd_
                || teth_.code.length == 0
                || tusd_.code.length == 0
        ) {
            revert InvalidConfiguration();
        }

        operator = operator_;
        teth = teth_;
        tusd = tusd_;
    }

    modifier onlyOperator() {
        if (msg.sender != operator) {
            revert UnauthorizedOperator();
        }
        _;
    }

    function deposit(
        address token,
        uint256 amount,
        bytes16 accountId
    ) external nonReentrant {
        _checkToken(token);

        if (amount == 0) revert InvalidAmount();
        if (accountId == bytes16(0)) revert InvalidAccountId();

        IERC20 asset = IERC20(token);
        uint256 beforeBalance = asset.balanceOf(address(this));

        asset.safeTransferFrom(msg.sender, address(this), amount);

        uint256 afterBalance = asset.balanceOf(address(this));

        // Reject tokens that deliver less or more than requested.
        if (
            afterBalance < beforeBalance
                || afterBalance - beforeBalance != amount
        ) {
            revert UnexpectedTokenBalance();
        }

        emit Deposited(accountId, token, msg.sender, amount);
    }

    function withdraw(
        bytes32 withdrawalId,
        address token,
        address recipient,
        uint256 amount
    ) external onlyOperator nonReentrant {
        _checkToken(token);

        if (withdrawalId == bytes32(0)) revert InvalidWithdrawalId();
        if (amount == 0) revert InvalidAmount();

        if (recipient == address(0) || recipient == address(this)) {
            revert InvalidRecipient();
        }

        if (processedWithdrawals[withdrawalId]) {
            revert WithdrawalAlreadyProcessed();
        }

        // Record the effect before the external transfer.
        // Any later revert rolls this change back too.
        processedWithdrawals[withdrawalId] = true;

        IERC20 asset = IERC20(token);

        uint256 vaultBefore = asset.balanceOf(address(this));
        uint256 recipientBefore = asset.balanceOf(recipient);

        asset.safeTransfer(recipient, amount);

        uint256 vaultAfter = asset.balanceOf(address(this));
        uint256 recipientAfter = asset.balanceOf(recipient);

        if (
            vaultAfter > vaultBefore
                || vaultBefore - vaultAfter != amount
                || recipientAfter < recipientBefore
                || recipientAfter - recipientBefore != amount
        ) {
            revert UnexpectedTokenBalance();
        }

        emit Withdrawn(withdrawalId, token, recipient, amount);
    }

    function _checkToken(address token) private view {
        if (token != teth && token != tusd) {
            revert UnsupportedToken();
        }
    }
}