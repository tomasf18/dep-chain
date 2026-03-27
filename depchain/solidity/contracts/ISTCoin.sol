// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

import "../node_modules/@openzeppelin/contracts/token/ERC20/ERC20.sol";

contract ISTCoin is ERC20 {
    uint8 private constant _DECIMALS = 2;   // each token can be divided into 100 sub-units 
                                            // (1 IST = 100 base units; 0.01 IST = 1 base unit (the smallest divisible amount))

    // we want to mint 100 million human-readable tokens to the initial owner
    // but internally, the contract stores everything in base units
    // so we multiply by 10^2 to convert to base units
    uint256 public constant INITIAL_SUPPLY = 100_000_000 * 10 ** _DECIMALS;

    error ApproveDisabledUseIncreaseDecrease();
    error DecreasedAllowanceBelowZero();

    // understand this constructor as "mint the entire supply to the initial owner"; more info here: https://forum.openzeppelin.com/t/how-to-implement-erc20-supply-mechanisms/226
    constructor(address initialOwner) ERC20("IST Coin", "IST") {
        _mint(initialOwner, INITIAL_SUPPLY);
    }


    /**
     * @dev Returns the number of decimals used to get its user representation.
     * For example, if `decimals` equals `2`, a balance of `505` tokens should
     * be displayed to a user as `5.05` (`505 / 10 ** 2`).
     */
    function decimals() public pure override returns (uint8) {
        return _DECIMALS;
    }

    /**
     * Safer allowance increase: additive update, avoids the classic
     * nonzero->nonzero approval replacement race.
     */
    function increaseAllowance(address spender, uint256 addedValue) public returns (bool) {
        address owner = _msgSender();
        _approve(owner, spender, allowance(owner, spender) + addedValue);
        return true;
    }

    /**
     * Safer allowance decrease: subtractive update.
     */
    function decreaseAllowance(address spender, uint256 subtractedValue) public returns (bool) {
        address owner = _msgSender();
        uint256 currentAllowance = allowance(owner, spender);

        if (currentAllowance < subtractedValue) {
            revert DecreasedAllowanceBelowZero();
        }

        unchecked {
            _approve(owner, spender, currentAllowance - subtractedValue);
        }

        return true;
    }

    /**
     * Keep the ERC-20 approve entrypoint, but forbid unsafe nonzero->nonzero
     * allowance replacement. This forces clients toward the safer API.
     *
     * Allowed:
     *   0   -> X
     *   X   -> 0
     *
     * Rejected:
     *   X   -> Y, where X != 0 and Y != 0
     */
    function approve(address spender, uint256 value) public override returns (bool) {
        address owner = _msgSender();
        uint256 currentAllowance = allowance(owner, spender);

        if (!(currentAllowance == 0 || value == 0)) {
            revert ApproveDisabledUseIncreaseDecrease();
        }

        _approve(owner, spender, value);
        return true;
    }
}