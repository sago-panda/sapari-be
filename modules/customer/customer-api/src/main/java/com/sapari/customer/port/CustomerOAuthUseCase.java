package com.sapari.customer.port;

import com.sapari.customer.command.CustomerOAuthCommand;
import com.sapari.customer.view.CustomerOAuthResult;

public interface CustomerOAuthUseCase {

    CustomerOAuthResult handleOAuthSuccess(CustomerOAuthCommand command);
}
