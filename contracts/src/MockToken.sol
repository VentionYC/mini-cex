// SPDX-License-Identifier: MIT
pragma solidity 0.8.38;

import {ERC20} from "@openzeppelin/contracts/token/ERC20/ERC20.sol";

/// @notice Mock asset for local development and testnet demonstrations.
contract MockToken is ERC20 {
    error UnsupportedDecimals(uint8 supplied);

    uint8 private immutable tokenDecimals;

    constructor(
        string memory name_,
        string memory symbol_,
        uint8 decimals_,
        address initialHolder,
        uint256 initialSupplyUnits
    ) ERC20(name_, symbol_) {
        if (decimals_ > 18) {
            revert UnsupportedDecimals(decimals_);
        }

        tokenDecimals = decimals_;
        _mint(initialHolder, initialSupplyUnits);
    }

    function decimals() public view override returns (uint8) {
        return tokenDecimals;
    }
}
