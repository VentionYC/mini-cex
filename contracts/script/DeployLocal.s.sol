// SPDX-License-Identifier: MIT
pragma solidity 0.8.38;

import {Script} from "forge-std/Script.sol";
import {console2} from "forge-std/console2.sol";

import {MockToken} from "../src/MockToken.sol";
import {ExchangeVault} from "../src/ExchangeVault.sol";

contract DeployLocal is Script {
    function run() external {
        require(block.chainid == 31337, "Local chain only");

        address deployer = vm.envAddress("DEPLOYER_ADDRESS");
        address operator = vm.envAddress("OPERATOR_ADDRESS");
        address trader = vm.envAddress("TRADER_ADDRESS");

        require(
            deployer != address(0)
                && operator != address(0)
                && trader != address(0),
            "Addresses must be nonzero"
        );

        require(
            deployer != operator
                && deployer != trader
                && operator != trader,
            "Use separate demo accounts"
        );
  
        vm.startBroadcast(deployer);

        MockToken teth = new MockToken(
            "Mini CEX Mock Ether",
            "TETH",
            18,
            trader,
            1_000 * 10 ** 18
        );

        MockToken tusd = new MockToken(
            "Mini CEX Mock USD",
            "TUSD",
            6,
            trader,
            1_000_000 * 10 ** 6
        );

        ExchangeVault vault = new ExchangeVault(
            operator,
            address(teth),
            address(tusd)
        );

        vm.stopBroadcast();

        console2.log("DEPLOYER:", deployer);
        console2.log("OPERATOR:", operator);
        console2.log("TRADER:", trader);
        console2.log("TETH:", address(teth));
        console2.log("TUSD:", address(tusd));
        console2.log("VAULT:", address(vault));
    }
}